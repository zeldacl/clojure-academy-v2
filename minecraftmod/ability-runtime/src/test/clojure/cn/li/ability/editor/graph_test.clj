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
