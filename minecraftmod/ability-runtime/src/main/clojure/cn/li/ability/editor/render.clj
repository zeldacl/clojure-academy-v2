(ns cn.li.ability.editor.render
  "Pure geometry helpers for drawing the node editor's canvas as a flat
   COMPOSITE-item vector (cn.li.presentation.core's UiOp/COMPOSITE, the
   same mechanism ac's existing skill_tree.clj already uses to paint a
   graph -- see the node-editor plan's §1.4 for that precedent).

   SCOPE for this first visual iteration (graph->composite-items):
   renders the EXEC statement chain only, one box per statement
   (including nested when/each/if bodies, indented), each box's label a
   full one-line rendering of that statement's own DSL text (cn.li.
   ability.editor.graph/stmt-text -- literally what saving would print,
   so a label can never show something structurally different from the
   real content) rather than a separate wired sub-node per nested pure
   expression. A full Blueprint-style per-expression node graph is more
   visually complex and, critically, not verifiable without an actual
   screen to look at -- this ships the part whose correctness (every
   statement gets exactly one box, nesting reads top-to-bottom with
   indentation, sequential wires connect the flow) is provable by a unit
   test today; expression-level sub-wiring is a real, deliberate
   follow-up once the exec-chain view has been used in-game and the
   extra complexity is worth it, not a silent gap."
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

(defn- node-composite-items [nodes nid pos]
  (let [{:keys [stmt]} (get nodes nid)
        x (:x pos) y (:y pos)
        text (graph/stmt-text nodes nid)
        text (if (> (count text) 64) (str (subs text 0 61) "...") text)]
    [{:kind :quad :role :node-body :nid nid :x x :y y :w node-box-width :h node-box-height
      :rgba (box-color stmt)}
     {:kind :text :role :node-label :nid nid :x (+ x 4.0) :y (+ y 3.0) :text text :rgba 0xFFFFFFFF}]))

(defn graph->composite-items
  "graph (cn.li.ability.editor.graph/form->graph's output), stored-layout
   -> a flat composite-item vector for a :repeater-bound canvas (see
   this namespace's own docstring for the exec-chain-only scope of this
   first iteration). Each node contributes a :quad (:role :node-body,
   the click target) + a :text label; consecutive statements (including
   into/out-of a nested when/each/if body) get a connecting wire. Every
   item carries :nid (and :role) so a click handler can resolve the
   :index the presentation runtime hands back into a real hit
   classification (cn.li.ability.editor.hit's {:target :node :nid ..})
   without a second lookup table."
  [graph stored-layout]
  (let [flat (graph/exec-flatten graph)
        layout (merge (exec-default-layout flat) stored-layout)
        nodes (:nodes graph)
        node-items (mapcat (fn [{:keys [nid]}] (node-composite-items nodes nid (get layout nid))) flat)
        wire-items (mapcat (fn [[{a :nid} {b :nid}]]
                             (let [pa (get layout a) pb (get layout b)]
                               (wire-quads (+ (:x pa) (/ node-box-width 2.0)) (+ (:y pa) node-box-height)
                                          (+ (:x pb) (/ node-box-width 2.0)) (:y pb)
                                          wire-thickness 0xFFAAAAAA)))
                           (partition 2 1 flat))]
    (vec (concat node-items wire-items))))
