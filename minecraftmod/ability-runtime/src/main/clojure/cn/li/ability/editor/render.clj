(ns cn.li.ability.editor.render
  "Pure geometry helpers for drawing the node editor's canvas as a flat
   COMPOSITE-item vector (cn.li.presentation.core's UiOp/COMPOSITE, the
   same mechanism ac's existing skill_tree.clj already uses to paint a
   graph -- see the node-editor plan's §1.4 for that precedent).

   SCOPE for this visual iteration (graph->composite-items):
   renders the EXEC statement chain as the primary top-to-bottom flow and
   renders every referenced pure expression in a secondary column. Expression
   boxes expose an output pin and statement boxes expose semantic input pins,
   so value wires are structurally editable without pretending an exec node is
   an expression. The view is intentionally hybrid rather than a full Blueprint
   canvas: it keeps the common control-flow path compact while making data
   dependencies visible and testable.

   Canvas labels use graph/stmt-label (short plain words like bind hit /
   damage), not raw DSL. Full stmt-text remains for inspectors that need the
   exact printable form."
  (:require [cn.li.ability.editor.graph :as graph]))

(defn wire-quads
  "x1 y1 x2 y2 thickness rgba -> three axis-aligned :quad composite items
   forming a horizontal-vertical-horizontal orthogonal connector. NOT the
   containing-rectangle approach cn.li.ac.ability.client.screens.skill-
   tree's own connection-items uses (skill_tree.clj:227-235) -- that
   renders a diagonal edge as one filled bounding-box rectangle, a real
   visual bug for anything but a perfectly horizontal/vertical pair. All
   three segments here are axis-aligned by construction, so :quad (the
   only rect primitive this canvas needs) stays pixel-accurate for any
   x1/y1/x2/y2, no new render primitive required."
  [x1 y1 x2 y2 thickness rgba]
  (let [x1 (double x1) y1 (double y1) x2 (double x2) y2 (double y2) thickness (double thickness)
        mx (/ (+ x1 x2) 2.0)
        h (/ thickness 2.0)]
    [{:kind :quad :x (min x1 mx) :y (- y1 h) :w (Math/abs (- mx x1)) :h thickness :rgba rgba}
     {:kind :quad :x (- mx h) :y (min y1 y2) :w thickness :h (Math/abs (- y2 y1)) :rgba rgba}
     {:kind :quad :x (min mx x2) :y (- y2 h) :w (Math/abs (- x2 mx)) :h thickness :rgba rgba}]))

(def ^:private default-node-spacing-x 160.0)
(def ^:private default-node-spacing-y 56.0)
(def ^:private default-column-width 6)

(defn default-layout
  "nid-seq (e.g. a graph's :order, in the stable order form->graph built
   them) -> {nid {:x :y}} -- a simple grid fallback for any nid absent
   from the real layout sidecar (a brand-new node just added, or the
   file's very first open before any sidecar exists). Deterministic by
   nid order, not meant to look good -- just to guarantee EVERY node has
   SOME position so rendering never has to special-case a missing
   layout entry."
  [nid-seq]
  (into {}
        (map-indexed (fn [i nid]
                       [nid {:x (* default-node-spacing-x (double (mod i default-column-width)))
                             :y (* default-node-spacing-y (double (quot i default-column-width)))}]))
        nid-seq))

(defn resolve-layout
  "stored-layout ({nid {:x :y ...}}, the editor's layout sidecar --
   cn.li.node.nid/collect gives the nid set it should be keyed by),
   nid-seq -> a COMPLETE layout covering every nid in nid-seq, falling
   back to default-layout's grid position for any nid stored-layout does
   not cover. stored-layout's entries win verbatim where present (an
   author's own placement is never overridden by the fallback)."
  [stored-layout nid-seq]
  (let [fallback (default-layout nid-seq)]
    (into {} (map (fn [nid] [nid (merge (get fallback nid) (get stored-layout nid))])) nid-seq)))

;; --- exec-chain composite rendering (first visual iteration) -----------

(def ^:private node-box-width 220.0)
(def ^:private node-box-height 16.0)
(def ^:private expr-box-width 150.0)
(def ^:private node-row-height 20.0)
(def ^:private node-indent-x 18.0)
(def ^:private wire-thickness 1.5)

(defn exec-default-layout
  "flat (cn.li.ability.editor.graph/exec-flatten's output) -> {nid {:x
   :y}}: top-to-bottom in program order, each nested level indented --
   the exec chain's natural reading order, distinct from default-
   layout's plain grid (which has no notion of program order or
   nesting, only used as data.clj's dimension-agnostic fallback)."
  [flat]
  (into {}
        (map-indexed (fn [i {:keys [nid depth]}]
                       [nid {:x (* node-indent-x (double depth))
                             :y (* node-row-height (double i))}]))
        flat))

(def ^:private stmt-colors
  {:let 0xFF3A5A78 :call 0xFF3A6E4A :when 0xFF7A5A2A :if 0xFF7A5A2A :each 0xFF6A3A7A
   :finish 0xFF7A2A2A :state! 0xFF4A4A7A :set! 0xFF4A4A7A :event! 0xFF2A6A6A :vfx! 0xFF6A2A6A})

(defn- box-color [stmt] (get stmt-colors stmt 0xFF444444))

(defn expr-default-layout
  "data-node-ids -> deterministic secondary-column positions."
  [nids]
  (into {}
        (map-indexed (fn [i nid]
                       [nid {:x (+ 280.0 (* 160.0 (double (mod i 3))))
                             :y (* node-row-height (double (quot i 3)))}]))
        nids))

(defn- expr-composite-items [nodes nid pos]
  (let [x (:x pos) y (:y pos)
        text (graph/expr-text nodes nid)
        text (if (> (count text) 42) (str (subs text 0 39) "...") text)]
    [{:kind :quad :role :expr-body :nid nid :x x :y y :w expr-box-width :h node-box-height
      :rgba 0xFF34495E}
     {:kind :text :role :expr-label :nid nid :x (+ x 4.0) :y (+ y 3.0)
      :text text :rgba 0xFFE8F1F8}
     {:kind :quad :role :pin :target :pin :nid nid :pin :out :key :result
      :x (+ x expr-box-width) :y (+ y 5.0) :w 5.0 :h 5.0 :rgba 0xFFFFCC66}]))

(defn- exec-inputs [node]
  (let [stmt (:stmt node)]
    (case stmt
      :let (when (:rhs node) [[:rhs (:rhs node)]])
      :when (when (:cond node) [[:cond (:cond node)]])
      :each (when (:coll node) [[:coll (:coll node)]])
      (:state! :set!) (when (:value node) [[:value (:value node)]])
      :call (if (map? (:args node)) (seq (:args node))
                (map-indexed vector (:args node)))
      (:event! :vfx!) (seq (:fields node))
      [])))

(defn- exec-pin-items [node]
  (mapv (fn [[key _]]
          {:kind :quad :role :pin :target :pin :nid (:nid node) :pin :in :key key
           :x -5.0 :y 5.0 :w 5.0 :h 5.0 :rgba 0xFF66CCFF})
        (exec-inputs node)))

(defn- node-composite-items [nodes nid pos]
  (let [{:keys [stmt]} (get nodes nid)
        x (:x pos) y (:y pos)
        ;; stmt-label already clips to graph/label-max, so no truncation here.
        text (graph/stmt-label nodes nid)
        pins (map (fn [pin] (assoc pin :x (+ x (:x pin)) :y (+ y (:y pin))))
                  (exec-pin-items (assoc (get nodes nid) :nid nid)))]
    (vec (concat
          [{:kind :quad :role :node-body :nid nid :x x :y y :w node-box-width :h node-box-height
            :rgba (box-color stmt)}
           {:kind :text :role :node-label :nid nid :x (+ x 4.0) :y (+ y 3.0)
            :text text :rgba 0xFFFFFFFF}]
          pins))))

(defn- graph->composite-items*
  "Render a legacy surface graph using the existing composite geometry."
  [graph stored-layout]
  (let [flat (graph/exec-flatten graph)
        exec-ids (mapv :nid flat)
        data-ids (->> (:nodes graph) (keep (fn [[nid node]] (when (= :data (:kind node)) nid))) vec)
        layout (merge (exec-default-layout flat) (expr-default-layout data-ids) stored-layout)
        nodes (:nodes graph)
        node-items (mapcat (fn [{:keys [nid]}] (node-composite-items nodes nid (get layout nid))) flat)
        expr-items (mapcat (fn [nid] (expr-composite-items nodes nid (get layout nid))) data-ids)
        flow-wires (mapcat (fn [[{a :nid} {b :nid}]]
                             (let [pa (get layout a) pb (get layout b)]
                               (wire-quads (+ (:x pa) (/ node-box-width 2.0)) (+ (:y pa) node-box-height)
                                           (+ (:x pb) (/ node-box-width 2.0)) (:y pb)
                                           wire-thickness 0xFFAAAAAA)))
                           (partition 2 1 flat))
        value-wires (mapcat (fn [{:keys [nid] :as node}]
                              (mapcat (fn [[key src]]
                                        (when (contains? nodes src)
                                          (let [pa (get layout src) pb (get layout nid)]
                                            (map #(assoc % :role :value-wire :to-key key)
                                                 (wire-quads (+ (:x pa) expr-box-width) (+ (:y pa) 8.0)
                                                             (- (:x pb) 5.0) (+ (:y pb) 8.0)
                                                             wire-thickness 0xFF66CCFF)))))
                                      (exec-inputs node)))
                            (map #(get nodes %) exec-ids))]
    (mapv #(assoc % :local-x 0.0 :local-y 0.0)
          (concat expr-items node-items value-wires flow-wires))))

(defn graph->composite-items
  "Render both legacy surface graphs and persisted V4 node/link graphs."
  [graph stored-layout]
  (if (:order graph)
    (graph->composite-items* graph stored-layout)
    (let [nodes (:nodes graph)
          nids (vec (keys nodes))
          layout (resolve-layout stored-layout nids)
          links (:links graph)
          incoming (group-by #(second (:to %)) (filter #(= :data (:kind %)) links))
          exec-node? #(contains? #{:start :component :branch :merge :foreach :repeat :loop-end :end :local-set} (:type %))
          data-node? #(contains? #{:literal :context-ref :parameter-ref :state-ref :local-get} (:type %))
          node-height (fn [n]
                        (+ 30.0 (* 14.0 (count (or (:inputs n) {})))))
          title (fn [n]
                  (let [t (:type n)]
                    (if (= :component t) (str (:component n)) (name t))))
          node-items (mapcat (fn [[nid n]]
                               (let [{:keys [x y]} (get layout nid)
                                     h (node-height n)
                                     input-ports (keys (or (:inputs n) {}))]
                                 (concat
                                  [{:kind :quad :role :node-body :nid nid :x x :y y :w node-box-width :h h
                                    :rgba (box-color (if (= :component (:type n)) :call (:type n)))}
                                   {:kind :text :role :node-label :nid nid :x (+ x 6.0) :y (+ y 5.0)
                                    :text (title n) :rgba 0xFFFFFFFF}
                                   {:kind :text :role :node-type :nid nid :x (+ x 6.0) :y (+ y 18.0)
                                    :text (str "[" (name (:type n)) "]") :rgba 0xFFB8C7D9}]
                                  (map-indexed (fn [i p]
                                                 [{:kind :text :role :param-label :nid nid :key p
                                                   :x (+ x 14.0) :y (+ y 32.0 (* i 14.0))
                                                   :text (str (name p) " = …") :rgba 0xFFD5E6F2}
                                                  {:kind :quad :role :pin :target :pin :nid nid :pin :in :key p
                                                   :x (- x 5.0) :y (+ y 31.0 (* i 14.0)) :w 5.0 :h 5.0 :rgba 0xFF66CCFF}])
                                               input-ports)
                                  (when (exec-node? n)
                                    [{:kind :quad :role :pin :target :pin :nid nid :pin :out :key :exec
                                      :x (+ x node-box-width) :y (+ y (/ h 2.0)) :w 5.0 :h 5.0 :rgba 0xFFFFCC66}]))))
                             nodes)
          data-items (mapcat (fn [[nid n]]
                               (let [{:keys [x y]} (get layout nid)]
                                 [{:kind :quad :role :data-body :nid nid :x x :y y :w expr-box-width :h 34.0 :rgba 0xFF34495E}
                                  {:kind :text :role :data-label :nid nid :x (+ x 6.0) :y (+ y 6.0)
                                   :text (str (name (:type n)) " " (or (:key n) (:value n) "")) :rgba 0xFFE8F1F8}
                                  {:kind :quad :role :pin :target :pin :nid nid :pin :out :key :value
                                   :x (+ x expr-box-width) :y (+ y 14.0) :w 5.0 :h 5.0 :rgba 0xFFFFCC66}]))
                            (filter (fn [[_ n]] (data-node? n)) nodes))
          wire-items (mapcat (fn [l]
                               (let [[from from-port] (:from l) [to to-port] (:to l)
                                     pa (get layout from) pb (get layout to)
                                     src-right (+ (:x pa) (if (data-node? (get nodes from)) expr-box-width node-box-width))
                                     dst-left (- (:x pb) 5.0)
                                     sy (+ (:y pa) (if (data-node? (get nodes from)) 14.0 (/ (node-height (get nodes from)) 2.0)))
                                     dy (+ (:y pb) 32.0)]
                                 (map #(assoc % :role (if (= :data (:kind l)) :value-wire :exec-wire)
                                                :from from :to to :from-port from-port :to-port to-port)
                                      (wire-quads src-right sy dst-left dy wire-thickness
                                                  (if (= :data (:kind l)) 0xFF66CCFF 0xFFAAAAAA)))))
                             links)]
      (mapv #(assoc % :local-x 0.0 :local-y 0.0)
            (concat node-items data-items wire-items)))))
