(ns cn.li.node.cost-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.cost :as cost]
            [cn.li.node.test-fixtures :as fx]))

(deftest unbounded-loop-poisons-max-iterations-to-nil-test
  ;; No literal :limit on the entities call this time (a runtime-varying
  ;; radius/expression would have the same effect) -- static analysis must
  ;; report "unknown", not silently read as zero or as the vocab default.
  (let [doc (surface/parse
             "{:ability :unbounded :tunables {:aoe {:type :double}}
               :do [(let xs (target/entities {:center [0.0 0.0 0.0] :radius $aoe}))
                    (each t xs (cooldown/start {:name :main :ticks 1}))
                    (finish {:outcome :performed})]}")
        ir (compile/compile! doc fx/opts)
        result (cost/analyze ir fx/vocab)]
    (is (nil? (:max-iterations result))
        "a missing literal :limit must poison the whole bound to nil, not read as the vocab default")))

(deftest pure-instructions-cost-nothing-test
  (let [doc (surface/parse
             "{:ability :pure-only :tunables {:range {:type :double}}
               :do [(let v (vec3/add ?caster/eye (vec3/scale ?caster/aim $range)))
                    (finish {:outcome :performed})]}")
        ir (compile/compile! doc fx/opts)
        result (cost/analyze ir fx/vocab)]
    (is (= 0 (:complexity result)))
    (is (= 0 (:host-commands result)))
    (is (= #{} (:effects result)))))
