(ns cn.li.ability.editor.render-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.render :as render]
            [cn.li.ability.editor.graph :as graph]
            [cn.li.node.surface :as surface]))

(deftest wire-quads-are-all-axis-aligned-test
  (let [quads (render/wire-quads 10.0 20.0 200.0 80.0 2.0 0xFFFFFFFF)]
    (is (= 3 (count quads)))
    (is (every? #(and (>= (:w %) 0.0) (>= (:h %) 0.0)) quads))))

(deftest wire-quads-degenerate-same-point-does-not-throw-test
  (let [quads (render/wire-quads 5.0 5.0 5.0 5.0 2.0 0xFF000000)]
    (is (= 3 (count quads)))))

(deftest default-layout-covers-every-nid-with-no-duplicates-test
  (let [nids ["n1" "n2" "n3" "n4" "n5" "n6" "n7" "n8"]
        layout (render/default-layout nids)]
    (is (= (set nids) (set (keys layout))))
    (is (every? #(and (contains? % :x) (contains? % :y)) (vals layout)))
    ;; distinct positions -- no two nodes stacked exactly on top of each other
    (is (= (count nids) (count (set (vals layout)))))))

(deftest resolve-layout-prefers-stored-position-over-fallback-test
  (let [nids ["n1" "n2"]
        stored {"n1" {:x 999.0 :y 999.0}}
        resolved (render/resolve-layout stored nids)]
    (is (= {:x 999.0 :y 999.0} (get resolved "n1")))
    (is (contains? resolved "n2"))
    (is (not= {:x 999.0 :y 999.0} (get resolved "n2")))))

(deftest resolve-layout-handles-empty-stored-layout-test
  (let [nids ["n1" "n2" "n3"]
        resolved (render/resolve-layout {} nids)]
    (is (= 3 (count resolved)))))

(defn- sample-graph []
  (graph/form->graph (:do (surface/read-doc
                            "{:ability :t :do
                               [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                                (when (:entity-id hit)
                                  (combat/damage {:target (:entity-id hit) :amount $damage}))
                                (finish {:outcome :performed})]}"))))

(deftest exec-default-layout-indents-nested-statements-test
  (let [flat (graph/exec-flatten (sample-graph))
        layout (render/exec-default-layout flat)
        depths (into {} (map (fn [{:keys [nid depth]}] [nid depth])) flat)]
    (doseq [[nid pos] layout]
      (is (= (* 18.0 (double (get depths nid))) (:x pos))))))

(deftest format-param-value-formats-common-shapes-test
  (is (= "nil" (#'render/format-param-value nil)))
  (is (= "foo" (#'render/format-param-value :foo)))
  (is (= "3" (#'render/format-param-value 3)))
  (is (= "(1, 2, 3)" (#'render/format-param-value [1 2 3])))
  (is (= "{...}" (#'render/format-param-value {:a 1})))
  (is (= "[...]" (#'render/format-param-value [1 2 3 4]))))

;; P4: every :component node used to render the literal string "[component]"
;; as its second line regardless of which component -- box-color also
;; collapses every :component to the same :call color, so that line
;; distinguished nothing. It's now either the first bound parameter or
;; omitted entirely (see render.clj's component-summary).
(defn- v4-graph-with-component [inputs]
  {:nodes {:n/start {:nid :n/start :type :start}
           :n/call {:nid :n/call :type :component :component "combat/damage" :inputs inputs}
           :n/end {:nid :n/end :type :end}}
   :links []})

(deftest component-nodes-never-render-the-literal-component-tag-test
  (let [items (render/graph->composite-items (v4-graph-with-component {:target :n/hit :amount 5}) {})]
    (is (not-any? #(= "[component]" (:text %)) items))))

(deftest component-node-type-line-shows-first-param-when-present-test
  (let [items (render/graph->composite-items (v4-graph-with-component {:amount 5}) {})
        type-lines (filter #(and (= :node-type (:role %)) (= :n/call (:nid %))) items)]
    (is (seq type-lines))
    (is (every? #(re-find #" = " (:text %)) type-lines))))

(deftest component-node-type-line-is-omitted-with-no-inputs-test
  (let [items (render/graph->composite-items (v4-graph-with-component {}) {})]
    (is (not-any? #(and (= :node-type (:role %)) (= :n/call (:nid %))) items))))

(deftest graph->composite-items-produces-one-body-and-label-per-exec-node-test
  (let [g (sample-graph)
        items (render/graph->composite-items g {})
        bodies (filter #(= :node-body (:role %)) items)
        labels (filter #(= :node-label (:role %)) items)]
    (is (= (count (graph/exec-flatten g)) (count bodies) (count labels)))
    (is (every? :nid bodies))
    (is (every? string? (map :text labels)))
    (is (every? #(<= (count %) 28) (map :text labels)))
    (is (not-any? #(re-find #"^\(let " %) (map :text labels)))))

(deftest graph->composite-items-includes-wires-between-statements-test
  (let [g (sample-graph)
        flat (graph/exec-flatten g)
        items (render/graph->composite-items g {})
        wires (filter #(and (= :quad (:kind %)) (not (:role %))) items)]
    ;; N flattened exec entries (let/when/nested-damage/finish = 4) ->
    ;; N-1 consecutive pairs -> 3 quads (wire-quads) each.
    (is (= (* 3 (dec (count flat))) (count wires)))))

(deftest graph->composite-items-provides-local-composite-offsets-test
  (let [items (render/graph->composite-items (sample-graph) {})]
    (is (seq items))
    (is (every? #(and (= 0.0 (:local-x %))
                      (= 0.0 (:local-y %)))
                items)
        "canvas items are painted local to their item-sized hit wrapper")))
(deftest graph->composite-items-respects-stored-layout-override-test
  (let [g (sample-graph)
        first-nid (:nid (first (graph/exec-flatten g)))
        items (render/graph->composite-items g {first-nid {:x 500.0 :y 500.0}})
        body (first (filter #(and (= :node-body (:role %)) (= first-nid (:nid %))) items))]
    (is (= 500.0 (:x body)))
    (is (= 500.0 (:y body)))))

(deftest graph->composite-items-renders-expression-pins-and-value-wires-test
  (let [g (sample-graph)
        items (render/graph->composite-items g {})
        expr-bodies (filter #(= :expr-body (:role %)) items)
        pins (filter #(= :pin (:role %)) items)
        value-wires (filter #(= :value-wire (:role %)) items)]
    (is (seq expr-bodies))
    (is (some #(and (= :out (:pin %)) (= :data (:kind (get (:nodes g) (:nid %))))) pins))
    (is (some #(= :in (:pin %)) pins))
    (is (seq value-wires))))

(deftest v4-loop-nodes-render-body-and-completed-pins-test
  (let [g {:nodes {:n/start {:nid :n/start :type :start}
                   :n/each {:nid :n/each :type :foreach :limit 8}
                   :n/repeat {:nid :n/repeat :type :repeat :count 2}
                   :n/end {:nid :n/end :type :end}}
          :links []}
        items (render/graph->composite-items g {})
        pins (filter #(and (= :pin (:role %)) (= :out (:pin %))) items)]
    (is (some #(and (= :n/each (:nid %)) (= :body (:key %))) pins))
    (is (some #(and (= :n/each (:nid %)) (= :completed (:key %))) pins))
    (is (some #(and (= :n/repeat (:nid %)) (= :body (:key %))) pins))
    (is (some #(and (= :n/repeat (:nid %)) (= :completed (:key %))) pins))))
(deftest v4-sentinel-pins-match-execution-contract-test
  (let [g {:nodes {:n/start {:nid :n/start :type :start}
                   :n/end {:nid :n/end :type :end}}
           :links []}
        pins (filter #(= :pin (:role %))
                     (render/graph->composite-items g {}))]
    ;; Start is a source-only sentinel; end is a sink-only sentinel.  The
    ;; renderer must not expose phantom ports that the graph validator
    ;; rejects, otherwise users see an apparently connectable but invalid
    ;; endpoint in the Blueprint-style canvas.
    (is (some #(and (= :n/start (:nid %)) (= :out (:pin %)) (= :out (:key %))) pins))
    (is (not-any? #(and (= :n/start (:nid %)) (= :in (:pin %))) pins))
    (is (some #(and (= :n/end (:nid %)) (= :in (:pin %))) pins))
    (is (not-any? #(and (= :n/end (:nid %)) (= :out (:pin %))) pins))))
