(ns cn.li.ac.ability.cooldown-test
  (:require [cn.li.ac.ability.model.cooldown :as cd]))

(defn test-cooldown-max-and-tick
  []
  (let [d0 (cd/new-cooldown-data)
        d1 (cd/set-cooldown d0 :a :main 10)
        d2 (cd/set-cooldown d1 :a :main 3)
        d3 (nth (iterate cd/tick-cooldowns d2) 5)]
    (assert (= 10 (cd/get-remaining d2 :a :main)) "cooldown should use max(existing,new)")
    (assert (= 5 (cd/get-remaining d3 :a :main)) "cooldown should decrement per tick")))

(defn run-all-tests []
  (println "=== ability cooldown tests ===")
  (test-cooldown-max-and-tick)
  (println "ok"))
