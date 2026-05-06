(ns cn.li.ac.content.ability.teleporter.tp-skill-helper-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as h]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(deftest skill-exp-test
  (testing "missing player state yields 0.0"
    (is (= 0.0 (h/skill-exp "no-one" :any))))
  (testing "reads exp from ability data"
    (reset! ps/player-states {})
    (let [ad (-> (ad/new-ability-data)
                 (ad/learn-skill :foo)
                 (ad/set-skill-exp :foo 0.42))]
      (ps/set-player-state! "p1" {:ability-data ad})
      (is (= 0.42 (h/skill-exp "p1" :foo)))
      (reset! ps/player-states {}))))

(deftest player-look-and-position-nil-without-bindings-test
  (is (nil? (binding [raycast/*raycast* nil]
              (h/player-look-vec "p"))))
  (is (nil? (binding [teleportation/*teleportation* nil]
              (h/player-position "p")))))
