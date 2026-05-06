(ns cn.li.ac.ability.server.service.cooldown-service-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.model.cooldown :as model]
            [cn.li.ac.ability.server.service.cooldown :as svc]))

(deftest wrappers-delegate-to-model-test
  (let [d0 (model/new-cooldown-data)
        d1 (svc/set-main-cooldown d0 :arc-gen 10)
        d2 (svc/set-cooldown d1 :arc-gen :sub 3)
        d3 (svc/tick-cooldowns d2)]
    (is (true? (svc/in-main-cooldown? d1 :arc-gen)))
    (is (= 10 (svc/get-remaining d2 :arc-gen :main)))
    (is (= 9 (svc/get-remaining d3 :arc-gen :main)))
    (is (= 2 (svc/get-remaining d3 :arc-gen :sub)))))
