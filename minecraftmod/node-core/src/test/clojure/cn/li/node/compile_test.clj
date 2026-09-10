(ns cn.li.node.compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.cost :as cost]
            [cn.li.node.ir :as ir]
            [cn.li.node.test-fixtures :as fx]))

(deftest golden-thunder-bolt-compiles-test
  (testing "the full grammar -- let, when, each with a defn call inlined
            twice, field access, capability/tunable reads, a pure vec3
            expression, and finish -- compiles to structurally valid IR"
    (let [doc (surface/parse fx/thunder-bolt-text)
          ir (compile/compile! doc fx/opts)]
      (is (= :thunder-bolt (:id ir)))
      (is (contains? (:entries ir) :default))
      (is (map? (ir/validate! ir)))))) ; re-validate; compile! already did once internally

(deftest golden-thunder-bolt-cost-analysis-test
  (let [doc (surface/parse fx/thunder-bolt-text)
        ir (compile/compile! doc fx/opts)
        result (cost/analyze ir fx/vocab)]
    (testing "complexity is the sum of every :query/:action instruction's
              declared cost: raycast(2) + entities(2) + cooldown/start(1)
              + combat/damage(3) inlined twice via ac/strike (6) = 11"
      (is (= 11 (:complexity result))))
    (testing "host-commands counts only :action instructions (no return
              value queued against the host) -- damage x2 + cooldown/start"
      (is (= 3 (:host-commands result))))
    (testing "effects is the union across every query/action node touched"
      (is (= #{:world-read :world-write :owner-write} (:effects result))))
    (testing "the each loop's static :limit 24 argument is recovered without
              running the program"
      (is (= 24 (:max-iterations result))))))

(deftest literal-nil-for-concrete-typed-param-is-a-compile-error-test
  (testing "literal nil into a :double param must fail at compile, not as
            convert-to-:double received nil at dispatch"
    (let [fns (assoc fx/fns
                     :test/strike
                     {:params [{:name 'length :type :double}]
                      :body '[(finish {:outcome :performed})]})
          doc (surface/parse
               "{:ability :nil-arg :tunables {}
                 :do [(test/strike nil) (finish {:outcome :performed})]}")]
      (try
        (compile/compile! doc (assoc fx/opts :fns fns))
        (is false "expected compile! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :nil-typed-param (:code (ex-data e)))))))))

(deftest type-mismatch-is-reported-at-the-source-node-test
  (let [doc (surface/parse
             "{:ability :bad-type :tunables {:range {:type :double}}
               :do [(cooldown/start {:name $range :ticks 40})
                    (finish {:outcome :performed})]}")]
    (is (thrown? clojure.lang.ExceptionInfo (compile/compile! doc fx/opts)))
    (try
      (compile/compile! doc fx/opts)
      (is false "expected compile! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :type-mismatch (:code (ex-data e))))))))

(deftest unknown-tunable-and-capability-are-real-errors-test
  (doseq [[text code]
          [["{:ability :bad1 :tunables {} :do [(cooldown/start {:name :main :ticks $nope}) (finish {:outcome :performed})]}"
            :unknown-tunable]
           ["{:ability :bad2 :tunables {} :do [(target/raycast {:from ?nope/eye :dir ?nope/eye :distance 1}) (finish {:outcome :performed})]}"
            :unknown-capability]]]
    (let [doc (surface/parse text)]
      (try
        (compile/compile! doc fx/opts)
        (is false (str "expected compile! to throw for " code))
        (catch clojure.lang.ExceptionInfo e
          (is (= code (:code (ex-data e)))))))))

(deftest unreachable-code-after-finish-is-reported-test
  (let [doc (surface/parse
             "{:ability :dead-code :tunables {} :do
               [(finish {:outcome :performed})
                (cooldown/start {:name :main :ticks 1})]}")]
    (try
      (compile/compile! doc fx/opts)
      (is false "expected compile! to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :unreachable-code (:code (ex-data e))))))))

(deftest defn-with-returns-is-usable-as-an-expression-test
  (testing "a :defn declaring :returns can be called from a `let` RHS, its
            declared local's value becoming the call's own result -- the
            gap composite -> :defn conversion (S6) surfaced: composites
            like target/raycast-destination are fundamentally value-
            producing, not purely effectful like cn.li.node.ops's original
            ac/strike example"
    (let [fns (assoc fx/fns
                     :ac/double-hit
                     {:params [{:name 'hit :type :hit-result}]
                      :body '[(let doubled (vec3/scale (:position hit) 2.0))]
                      :returns 'doubled})
          opts (assoc fx/opts :fns fns)
          doc (surface/parse
               "{:ability :uses-return :tunables {:range {:type :double}}
                 :do [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                      (let far (ac/double-hit hit))
                      (finish {:outcome :performed})]}")
          ir (compile/compile! doc opts)]
      (is (map? (ir/validate! ir)))
      (testing "the returned register really is vec3/scale's result, not a dummy"
        (is (some #(and (= :pure (:op %)) (= :vec3/scale (:fn %))) (mapcat :instrs (:blocks ir))))))))

(deftest defn-void-call-still-reports-void-let-rhs-test
  (testing "a :defn with NO :returns stays void -- binding its call via
            `let` is still a real error, not silently allowed now"
    (let [doc (surface/parse
               "{:ability :void-bind :tunables {:damage {:type :double} :range {:type :double}}
                 :do [(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim :distance $range}))
                      (let x (ac/strike (:entity-id hit) $damage))
                      (finish {:outcome :performed})]}")]
      (try
        (compile/compile! doc fx/opts)
        (is false "expected compile! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :void-let-rhs (:code (ex-data e)))))))))

(deftest each-desugars-without-a-host-round-trip-test
  (testing "each lowers entirely to :pure/:copy/:branch/:jump -- iterating
            an already-produced list is deterministic, not a host query"
    (let [doc (surface/parse
               "{:ability :loop-only :tunables {}
                 :do [(let xs (target/entities {:center [0.0 0.0 0.0] :radius 4.0}))
                      (each t xs (cooldown/start {:name :main :ticks 1}))
                      (finish {:outcome :performed})]}")
          result (compile/compile! doc fx/opts)
          instrs (mapcat :instrs (:blocks result))]
      (is (some #(and (= :pure (:op %)) (= :collection/count (:fn %))) instrs))
      (is (some #(and (= :pure (:op %)) (= :collection/nth (:fn %))) instrs))
      (is (some #(and (= :pure (:op %)) (= :long/inc (:fn %))) instrs)))))
