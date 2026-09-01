(ns cn.li.presentation.core.test-artifact
  "Test-only artifact builder: flattens a friendly nested node spec into the
   same flat schema-5 EDN shape presentation-compiler produces. Duplicating
   a small, deliberately simplified slice of that flattening here (rather
   than depending on presentation-compiler, which depends on this module —
   the wrong direction) is exactly the role cn.li.presentation.core.engine's
   own NodeTableBuilder.java plays for the Java-side kernel tests.

   Node spec keys (all optional, defaults shown):
   {:op -1 :flags #{} :direction :none :justify :start :align-items :start
    :align-self :inherit :width [:auto 0.0] :height [:auto 0.0]
    :margin [0 0 0 0] :padding [0 0 0 0] :min-w 0 :min-h 0 :max-w 0 :max-h 0
    :gap 0.0 :aspect 0.0 :x 0.0 :y 0.0 :font-size 8.0 :rgba 0xFFFFFFFF
    :text nil :resource nil :key nil :bind {} :on {} :children []}"
  (:import [cn.li.mcmod.runtime.ui UiOp]))

(def ^:private flag-bits
  {:has-clip 1 :is-scroll 2 :is-collection 4 :hit-testable 8
   :has-visible-bind 16 :wrap 32 :focusable 64 :animated 128
   :opaque 256 :scrollbar 512 :has-direction 1024})

(defn- flags->int [flags] (reduce (fn [acc f] (bit-or acc (get flag-bits f 0))) 0 flags))

(def ^:private direction->int {:none 0 :row 1 :column 2})
(def ^:private justify->int {:start 0 :center 1 :end 2 :space-between 3 :space-around 4})
(def ^:private align->int {:inherit -1 :start 0 :center 1 :end 2 :stretch 3})
(def ^:private size-mode->int {:auto 0 :fixed 1 :pct 2 :weight 3 :fill 4})

(def ^:private defaults
  {:op -1 :flags #{} :direction :none :justify :start :align-items :start :align-self :inherit
   :width [:auto 0.0] :height [:auto 0.0]
   :margin [0.0 0.0 0.0 0.0] :padding [0.0 0.0 0.0 0.0]
   :min-w 0.0 :min-h 0.0 :max-w 0.0 :max-h 0.0
   :gap 0.0 :aspect 0.0 :x 0.0 :y 0.0 :font-size 8.0 :rgba 0xFFFFFFFF
   :text nil :resource nil :key nil :bind {} :on {} :children []})

(defn- flatten-tree [root]
  (let [rows (atom []) parent (atom []) first-child (atom []) next-sibling (atom []) child-count (atom [])]
    (letfn [(walk [node parent-idx]
              (let [node (merge defaults node)
                    idx (count @rows)]
                (swap! rows conj (dissoc node :children))
                (swap! parent conj parent-idx)
                (swap! first-child conj -1)
                (swap! next-sibling conj -1)
                (swap! child-count conj (count (:children node)))
                (let [child-idxs (mapv #(walk % idx) (:children node))]
                  (when (seq child-idxs)
                    (swap! first-child assoc idx (first child-idxs))
                    (doseq [[a b] (partition 2 1 child-idxs)] (swap! next-sibling assoc a b)))
                  idx)))]
      (walk root -1)
      {:rows @rows :parent @parent :first-child @first-child
       :next-sibling @next-sibling :child-count @child-count})))

(defn- state-path? [v] (and (vector? v) (= :state (first v)) (<= 2 (count v))))

(defn- assign-bindings [rows]
  (let [bindings (atom {})
        own (mapv (fn [node]
                    (into #{}
                          (keep (fn [[_ path]]
                                  (when (state-path? path)
                                    (or (get @bindings path)
                                        (let [id (count @bindings)] (swap! bindings assoc path id) id)))))
                          (:bind node)))
                  rows)]
    {:own own :bindings (->> @bindings (sort-by val) (mapv (fn [[p i]] {:id i :path p})) vec)}))

(defn- dep-masks [rows parent own binding-count]
  (let [n (count rows)
        words (max 1 (int (Math/ceil (/ (max 1 binding-count) 64.0))))
        mask (long-array (* n words))]
    (dotimes [i n]
      (doseq [id (nth own i)]
        (let [w (quot (int id) 64) b (mod (int id) 64) ix (+ (* i words) w)]
          (aset mask ix (bit-or (aget mask ix) (bit-shift-left 1 (int b)))))))
    (doseq [i (range (dec n) 0 -1)]
      (let [p (int (nth parent i))]
        (when (>= p 0)
          (dotimes [w words]
            (let [ci (+ (* i words) w) pi (+ (* p words) w)]
              (aset mask pi (bit-or (aget mask pi) (aget mask ci))))))))
    {:mask (vec mask) :words words}))

(defn- dedup-table [values]
  (let [seen (atom {})
        indices (mapv (fn [v]
                        (if (nil? v) -1
                            (or (get @seen v)
                                (let [id (count @seen)] (swap! seen assoc v id) id))))
                      values)]
    [indices (mapv first (sort-by second @seen))]))

(defn build
  "root-spec -> a schema-5 artifact map, ready for cn.li.presentation.core.nodetable/table-for."
  [view-id root-spec & {:keys [host]}]
  (let [flat (flatten-tree root-spec)
        rows (:rows flat)
        {:keys [own bindings]} (assign-bindings rows)
        {:keys [mask words]} (dep-masks rows (:parent flat) own (count bindings))
        [text-idx string-table] (dedup-table (mapv :text rows))
        [res-idx resources] (dedup-table (mapv :resource rows))]
    {:magic :pui5 :schema 5 :view-id view-id :source-hash "test"
     :host (or host {}) :state-schema {}
     :node-count (count rows)
     :node/op (mapv #(int (:op %)) rows)
     :node/parent (:parent flat)
     :node/first-child (:first-child flat)
     :node/next-sibling (:next-sibling flat)
     :node/child-count (:child-count flat)
     :node/flags (mapv #(flags->int (:flags %)) rows)
     :node/box (vec (mapcat (fn [{:keys [margin padding min-w min-h max-w max-h]}]
                              (concat margin padding [min-w min-h max-w max-h]))
                            rows))
     :node/width-mode (mapv #(size-mode->int (first (:width %))) rows)
     :node/width-value (mapv #(double (second (:width %))) rows)
     :node/height-mode (mapv #(size-mode->int (first (:height %))) rows)
     :node/height-value (mapv #(double (second (:height %))) rows)
     :node/gap (mapv :gap rows)
     :node/aspect (mapv :aspect rows)
     :node/declared-x (mapv :x rows)
     :node/declared-y (mapv :y rows)
     :node/direction (mapv #(direction->int (:direction %)) rows)
     :node/justify (mapv #(justify->int (:justify %)) rows)
     :node/align-items (mapv #(align->int (:align-items %)) rows)
     :node/align-self (mapv #(align->int (:align-self %)) rows)
     :node/font-size (mapv :font-size rows)
     :node/rgba (mapv :rgba rows)
     :node/text-index (vec text-idx)
     :node/res (vec res-idx)
     :node/style (vec (repeat (count rows) -1))
     :node/bind (vec (repeat (count rows) -1))
     :node/action (vec (repeat (count rows) -1))
     :node/anim (vec (repeat (count rows) -1))
     :node/dep-mask mask
     :mask-words words
     :binding-count (count bindings)
     :bindings bindings
     :actions []
     :style-table []
     :string-table (vec string-table)
     :resources (vec resources)
     :node/key (mapv :key rows)
     :node/bind-map (mapv :bind rows)
     :node/on-map (mapv :on rows)
     :node/semantics (vec (repeat (count rows) nil))
     :focus-order []
     :semantics {}
     :capabilities #{}}))
