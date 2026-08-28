(ns cn.li.ac.ability.server.service.delayed-projectiles-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.combat :as combat]
            [cn.li.ability.continuation :as continuation]))

(defn- runtime [] (combat/create-runtime {:execute-result! (fn [_ _] nil)}))

(deftest generic-beam-scheduler-keeps-bounded-deadline-test
  (let [runtime (runtime)
        request {:owner "owner" :world-id "world"
                 :origin {:x 0.0 :y 64.0 :z 0.0}
                 :destination {:x 0.0 :y 64.0 :z 15.0}
                 :damage 4.0 :damage-type :magic :delay-ticks 3}]
    (is (= :scheduled (:status (combat/schedule! runtime request))))
    (is (= 1 (count (continuation/pending runtime "owner"))))
    (is (= 3 (:ticks-left (first (continuation/pending runtime "owner")))))))

(deftest generic-beam-scheduler-clamps-zero-delay-test
  (let [runtime (runtime)
        request {:owner "owner" :world-id "world"
                 :origin {:x 0.0 :y 64.0 :z 0.0}
                 :destination {:x 0.0 :y 64.0 :z 1.0}
                 :damage 1.0 :delay-ticks 0}]
    (is (= :scheduled (:status (combat/schedule! runtime request))))
    (is (= 1 (:ticks-left (first (continuation/pending runtime "owner")))))))
