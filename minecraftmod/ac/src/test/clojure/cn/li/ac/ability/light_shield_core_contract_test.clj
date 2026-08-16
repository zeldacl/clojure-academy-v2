(ns cn.li.ac.ability.light-shield-core-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.light-shield-state :as shield]
            [cn.li.ac.ability.service.combat-runtime :as combat]))

(deftest combat-core-owns-light-shield-state-machine
  (let [started (shield/start 7.0)
        ticked (shield/tick started)
        [absorbed remaining amount] (shield/absorb ticked 20.0 12.0 12)
        ended (combat/apply-combat-domain-event
               {:light-shields {"p" absorbed}}
               {:type :light-shield-end :owner "p"})]
    (is (= 7.0 (:overload-floor started)))
    (is (= 1 (:ticks ticked)))
    (is (= 12 (:last-absorb-tick absorbed)))
    (is (= 8.0 remaining))
    (is (= 12.0 amount))
    (is (nil? (get-in ended [:light-shields "p"])))))
