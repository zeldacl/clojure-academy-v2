(ns cn.li.ac.ability.radiation-domain-effect-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-runtime :as combat]))

(deftest radiation-replace-is-owned-by-combat-domain
  (is (= {"target" {:rate 1.0}}
         (get-in (combat/apply-combat-domain-event
                  {}
                  {:type :radiation-replace
                   :source-player-id "owner"
                   :marks {"target" {:rate 1.0}}})
                 [:radiation-marks "owner"]))))
