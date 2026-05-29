(ns cn.li.ac.ability.server.service.cooldown-service-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.model.cooldown :as model]
            [cn.li.ac.ability.rules.cooldown-rules :as svc]))

(deftest wrappers-delegate-to-model-test
  (let [d0 (model/new-cooldown-data)
    d1 (:data (svc/set-cooldown d0 :arc-gen 10 :main))
    d2 (:data (svc/set-cooldown d1 :arc-gen 3 :sub))
    d3 (:data (svc/server-tick d2))]
  (is (true? (svc/in-cooldown? d1 :arc-gen :main)))
  (is (= 10 (svc/get-remaining-ticks d2 :arc-gen :main)))
  (is (= 9 (svc/get-remaining-ticks d3 :arc-gen :main)))
  (is (= 2 (svc/get-remaining-ticks d3 :arc-gen :sub)))))
