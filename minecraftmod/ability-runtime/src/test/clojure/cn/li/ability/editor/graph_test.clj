(ns cn.li.ability.editor.graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ability.editor.graph :as graph]
            [cn.li.node.surface :as surface]))

(defn- round-trip [dsl-text]
  (let [doc (surface/read-doc dsl-text)
        stmts (:do doc)
        g (graph/form->graph stmts)
        back (graph/graph->form g)]
    [stmts back g]))

(deftest simple-call-and-let-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                           (cooldown/start {:name :main :ticks 40})
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest nested-pure-expr-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let end (vec3/add ?caster/eye (vec3/scale ?caster/aim $range)))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest field-access-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                           (when (:entity-id hit)
                             (combat/damage {:target (:entity-id hit) :amount $damage}))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest each-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let targets (target/entities {:center ?caster/eye :radius $aoe :limit 24}))
                           (each t targets
                             (combat/damage {:target t :amount $damage}))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest each-with-index-binding-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let targets (target/entities {:center ?caster/eye :radius $aoe :limit 24}))
                           (each [t i] targets
                             (combat/damage {:target t :amount $damage}))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest state-write-set-and-event-vfx-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(state! :mode :armed)
                           (let x $range)
                           (set! x (math/add x 1.0))
                           (event! {:type :charge :amount x})
                           (vfx! {:effect-id :arc-strike :start ?caster/eye :end ?caster/eye})
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest map-and-vec-literal-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let cfg {:a 1 :b [1 2 3]})
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest positional-pure-op-call-is-a-single-call-node-test
  (let [g (graph/form->graph (:do (surface/read-doc
                                    "{:ability :t :do [(let x (vec3/add ?caster/eye ?caster/aim)) (finish {:outcome :performed})]}")))
        let-node (get (:nodes g) (first (:order g)))
        rhs-node (get (:nodes g) (:rhs let-node))]
    (is (= :call (:expr rhs-node)))
    (is (= :positional (:arg-shape rhs-node)))
    (is (= 'vec3/add (:op rhs-node)))
    (is (= 2 (count (:args rhs-node))))))

(deftest node-call-with-arg-map-is-a-single-call-node-test
  (let [g (graph/form->graph (:do (surface/read-doc
                                    "{:ability :t :do [(cooldown/start {:name :main :ticks 40}) (finish {:outcome :performed})]}")))
        call-node (get (:nodes g) (first (:order g)))]
    (is (= :call (:stmt call-node)))
    (is (= :map (:arg-shape call-node)))
    (is (contains? (:args call-node) :name))
    (is (contains? (:args call-node) :ticks))))

(deftest two-armed-if-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(if (bool/not true)
                             [(finish {:outcome :performed})]
                             [(finish {:outcome :cancelled})])]}")]
    (is (= stmts back))))

(deftest if-with-malformed-trailing-body-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"vectors"
       (graph/form->graph (:do (surface/read-doc
                                 "{:ability :t :do [(if (bool/not true) (finish {:outcome :performed}))]}"))))))

(deftest nested-if-inside-when-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                           (when (:entity-id hit)
                             (if (bool/not true)
                               [(combat/damage {:target (:entity-id hit) :amount $damage})]
                               [(finish {:outcome :cancelled})]))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest nested-when-inside-each-round-trip-test
  (let [[stmts back] (round-trip
                       "{:ability :t :do
                          [(let targets (target/entities {:center ?caster/eye :radius $aoe :limit 24}))
                           (each t targets
                             (when (bool/not true)
                               (combat/damage {:target t :amount $damage})))
                           (finish {:outcome :performed})]}")]
    (is (= stmts back))))

(deftest stmt-text-matches-what-graph-form-would-print-test
  (let [g (graph/form->graph (:do (surface/read-doc
                                    "{:ability :t :do [(cooldown/start {:name :main :ticks 40}) (finish {:outcome :performed})]}")))
        first-nid (first (:order g))]
    (is (= (pr-str (first (graph/graph->form g))) (graph/stmt-text (:nodes g) first-nid)))))

(deftest stmt-label-is-short-and-not-raw-dsl-test
  (let [g (graph/form->graph (:do (surface/read-doc
                                    "{:ability :t :do
                                       [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                                        (when (:entity-id hit)
                                          (combat/damage {:target (:entity-id hit) :amount $damage}))
                                        (finish {:outcome :performed})]}")))
        nodes (:nodes g)
        labels (mapv #(graph/stmt-label nodes (:nid %)) (graph/exec-flatten g))]
    (is (= ["bind hit" "when" "damage" "finish"] labels))
    (is (every? #(<= (count %) 28) labels))
    (is (not-any? #(re-find #"^\(let " %) labels))))

(deftest exec-flatten-walks-nested-bodies-with-increasing-depth-test
  (let [g (graph/form->graph (:do (surface/read-doc
                                    "{:ability :t :do
                                       [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                                        (when (:entity-id hit)
                                          (each t (target/entities {:center ?caster/eye :radius $aoe :limit 24})
                                            (combat/damage {:target t :amount $damage})))
                                        (finish {:outcome :performed})]}")))
        flat (graph/exec-flatten g)]
    (is (= 5 (count flat)))
    (is (= [0 0 1 2 0] (mapv :depth flat)))))

(deftest stamped-nid-survives-a-round-trip-test
  (let [doc (surface/read-doc
             "{:ability :t :do [^{:nid \"n7\"} (finish {:outcome :performed})]}")
        g (graph/form->graph (:do doc))
        back (graph/graph->form g)]
    (is (= "n7" (:nid (meta (first back)))))))

(deftest graph-structural-editing-add-connect-remove-test
  (let [g (graph/form->graph
           (:do (surface/read-doc
                 "{:ability :t :do [(let x 1) (finish {:outcome :performed})]}")))
         new (graph/add-node g {:nid "n-extra" :kind :exec :stmt :let :bind 'y :rhs nil})
         source (->> (:nodes new) (keep (fn [[nid node]] (when (= :data (:kind node)) nid))) first)
        target "n-extra"
        connected (graph/connect-wire new {:from-nid source :from-pin :out
                                           :to-nid target :to-pin :in :to-key :rhs})
        removed (graph/remove-node connected source)]
    (is (contains? (:nodes new) "n-extra"))
    (is (= source (get-in connected [:nodes target :rhs])))
    (is (not (contains? (:nodes removed) source)))
     (is (empty? (graph/validate-graph removed)))))

(deftest graph-diagnostics-find-dangling-wire-test
  (let [errors (graph/validate-graph
                {:nodes {"n1" {:nid "n1" :kind :exec :stmt :let :rhs "missing"}}
                 :order ["n1"]})]
    (is (= :dangling-reference (:code (first errors))))))

(deftest graph-connect-rejects-unknown-input-pin-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"target input pin"
       (graph/connect-wire
        {:nodes {"a" {:nid "a" :kind :data :expr :literal :value 1}
                 "b" {:nid "b" :kind :exec :stmt :finish :fields {}}}
         :order ["b"]}
         {:from-nid "a" :from-pin :out :to-nid "b" :to-pin :in :to-key :missing}))))

(deftest palette-insertion-creates-round-trippable-call-and-literals-test
  (let [g (graph/form->graph
           (:do (surface/read-doc "{:ability :t :do [(finish {:outcome :performed})]}")))
        inserted (graph/insert-palette-node
                  g {:id :combat/damage
                     :params {:amount {:type :float :default 2.0}}}
                  "palette-1")
        next-graph (:graph inserted)
        call (get-in next-graph [:nodes (:nid inserted)])
        form (graph/graph->form next-graph)]
    (is (= 2 (count (:order next-graph))))
    (is (= 3 (count (:nodes next-graph))))
    (is (= :call (:stmt call)))
    (is (= :data (:kind (get-in next-graph [:nodes (get-in call [:args :amount])]))))
    (is (some #(= 'combat/damage (first %)) form))))
