(ns cn.li.ac.ability.server.effect.beam-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.ac.ability.server.effect.core :as effect]))

(use-fixtures :once (fn [f] (effect/init-default-ops!) (f)))

(deftest beam-accepts-look-dir-with-delta-keys-test
  (let [evt {:ctx-id "ctx-dxyz"
             :player-id "p1"
             :world-id "w1"
             :eye-pos {:x 10.0 :y 64.0 :z 10.0}
             :look-dir {:dx 0.0 :dy 0.0 :dz 1.0}}
        out (effect/run-op! evt [:beam {:max-distance 8.0
                                        :visual-distance 6.0
                                        :damage 0.0
                                        :break-blocks? false
                                        :block-energy 0.0}])]
    (is (true? (get-in out [:beam-result :performed?])))
    (is (= 6.0 (double (get-in out [:beam-result :visual-distance]))))))

(deftest beam-accepts-look-dir-with-xyz-keys-test
  (let [evt {:ctx-id "ctx-xyz"
             :player-id "p1"
             :world-id "w1"
             :eye-pos {:x 10.0 :y 64.0 :z 10.0}
             :look-dir {:x 0.0 :y 0.0 :z 1.0}}
        out (effect/run-op! evt [:beam {:max-distance 8.0
                                        :visual-distance 6.0
                                        :damage 0.0
                                        :break-blocks? false
                                        :block-energy 0.0}])]
    (is (true? (get-in out [:beam-result :performed?])))
    (is (= 6.0 (double (get-in out [:beam-result :visual-distance]))))))
