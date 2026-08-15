(ns cn.li.ac.ability.light-shield-state-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.light-shield-state :as shield]))

(deftest absorb-interval-requires-more-than-configured-interval
  (let [state (assoc (shield/start 12.0) :ticks 18 :last-absorb-tick 0)]
    (is (false? (shield/eligible-absorb?
                 {:ticks 18 :last-absorb-tick 0 :interval 18
                  :front? true :damage 10.0})))
    (is (true? (shield/eligible-absorb?
                {:ticks 19 :last-absorb-tick 0 :interval 18
                 :front? true :damage 10.0})))
    (is (= 12.0 (:overload-floor state)))))

(deftest absorb-is-capped-and-cooldown-scales-with-held-ticks
  (let [[next-state remaining absorbed]
        (shield/absorb (shield/start 0.0) 40.0 15.0 19)]
    (is (= 19 (:last-absorb-tick next-state)))
    (is (= 25.0 remaining))
    (is (= 15.0 absorbed))
    (is (= 30 (shield/end-cooldown-ticks 20 0.5)))))

