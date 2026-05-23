(ns cn.li.ac.content.ability.teleporter.tp-skill-helper-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.model.ability :as ad]
            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as h]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
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

(defn- stub-raycast
  [look-vec raycast-result]
  (reify raycast/IRaycast
    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
    (raycast-entities [_ _ _ _ _ _ _ _ _] nil)
    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
    (get-player-look-vector [_ player-uuid]
      (when (= "p1" player-uuid) look-vec))
    (raycast-from-player [_ _player-uuid _max-dist _living?] raycast-result)))

(deftest player-look-vec-delegates-test
  (let [stub (stub-raycast {:x 0.0 :y 1.0 :z 0.0} nil)]
    (is (= {:x 0.0 :y 1.0 :z 0.0}
           (binding [raycast/*raycast* stub]
             (h/player-look-vec "p1"))))))

(deftest teleport-to-success-and-fall-reset-test
  (let [tp-calls (atom [])
        reset-calls (atom [])
        stub (reify teleportation/ITeleportation
               (teleport-player! [_ pid wid x y z]
                 (swap! tp-calls conj [pid wid x y z])
                 true)
               (teleport-with-entities! [_ _ _ _ _ _ _] {:success false :teleported-count 0})
               (reset-fall-damage! [_ pid]
                 (swap! reset-calls conj pid)
                 true)
               (get-player-position [_ _] nil)
               (get-player-dimension [_ _] nil))]
    (binding [teleportation/*teleportation* stub]
      (is (true? (h/teleport-to! "u1" "minecraft:overworld" 1.0 2.0 3.0))))
    (is (= [["u1" "minecraft:overworld" 1.0 2.0 3.0]] @tp-calls))
    (is (= ["u1"] @reset-calls))))

(deftest teleport-to-failure-skips-fall-reset-test
  (let [tp-called? (atom false)
        stub (reify teleportation/ITeleportation
               (teleport-player! [_ _ _ _ _ _]
                 (reset! tp-called? true)
                 false)
               (teleport-with-entities! [_ _ _ _ _ _ _] {:success false :teleported-count 0})
               (reset-fall-damage! [_ _] (is false "reset should not run"))
               (get-player-position [_ _] nil)
               (get-player-dimension [_ _] nil))]
    (binding [teleportation/*teleportation* stub]
      (is (false? (h/teleport-to! "u1" "w" 0 0 0))))
    (is (true? @tp-called?))))

(deftest raycast-entity-filters-self-and-nil-miss-test
  (let [hit {:hit-entity true :entity-uuid "other"}]
    (is (nil? (binding [raycast/*raycast* (stub-raycast nil nil)]
                (h/raycast-entity "p" 8.0))))
    (is (nil? (binding [raycast/*raycast* (stub-raycast nil hit)]
                (h/raycast-entity "other" 8.0))))
    (is (= hit (binding [raycast/*raycast* (stub-raycast nil hit)]
                  (h/raycast-entity "self-id" 8.0))))))

(deftest deal-magic-damage-plain-arity-test
  (let [last-args (atom nil)
        stub (reify entity-damage/IEntityDamage
               (apply-direct-damage! [_ w u dmg st]
                 (reset! last-args [w u dmg st])
                 true)
               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] ())
               (apply-reflection-damage! [_ _ _ _ _ _ _] ()))]
    (is (nil? (binding [entity-damage/*entity-damage* nil]
                (h/deal-magic-damage! "world" "e1" 5.5))))
    (binding [entity-damage/*entity-damage* stub]
      (is (true? (h/deal-magic-damage! "world" "e1" 5.5)))
      (is (= ["world" "e1" 5.5 :magic] @last-args)))))

(deftest deal-magic-damage-crit-branch-test
  (let [last-damage (atom nil)
        exp-calls (atom [])
        events (atom [])
        attacker "att"
        attacker-ad (-> (ad/new-ability-data)
                        (ad/learn-skill :dim-folding-theorem)
                        (ad/learn-skill :space-fluct)
                        (ad/set-skill-exp :dim-folding-theorem 1.0)
                        (ad/set-skill-exp :space-fluct 1.0))
        stub (reify entity-damage/IEntityDamage
               (apply-direct-damage! [_ _ _ dmg _]
                 (reset! last-damage dmg)
                 true)
               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] ())
               (apply-reflection-damage! [_ _ _ _ _ _ _] ()))]
    (reset! ps/player-states {})
    (try
      (ps/set-player-state! attacker {:ability-data attacker-ad})
      (binding [entity-damage/*entity-damage* stub]
        (with-redefs [rand (fn [] 0.0)
                      skill-effects/add-skill-exp! (fn [pid sid amount]
                                                     (swap! exp-calls conj [pid sid amount])
                                                     nil)
                      ach-dispatcher/trigger-custom-event! (fn [pid event-id]
                                                             (swap! events conj [pid event-id])
                                                             nil)]
          (let [result (h/deal-magic-damage! attacker "w" "victim" 10.0)]
            (is (= true (:critical? result)))
            (is (= 0 (:crit-level result)))
            (is (= 1.3 (double (:crit-rate result))))
            (is (= 10.0 (double (:damage-before result))))
            (is (= 13.0 (double (:damage-after result))))
            (is (= true (:applied? result)))
            (is (= ["teleporter.critical_attack"] (:events result))))))
      (is (= 13.0 (double @last-damage)))
      (is (= [["att" :dim-folding-theorem 0.005]
              ["att" :space-fluct 1.0E-4]]
             @exp-calls))
      (is (= [["att" "teleporter.critical_attack"]]
             @events))
      (finally
        (reset! ps/player-states {})))))

(deftest deal-magic-damage-non-crit-branch-test
  (let [last-damage (atom nil)
        attacker "att"
        attacker-ad (-> (ad/new-ability-data)
                        (ad/learn-skill :dim-folding-theorem)
                        (ad/learn-skill :space-fluct)
                        (ad/set-skill-exp :dim-folding-theorem 1.0)
                        (ad/set-skill-exp :space-fluct 1.0))
        stub (reify entity-damage/IEntityDamage
               (apply-direct-damage! [_ _ _ dmg _]
                 (reset! last-damage dmg)
                 true)
               (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] ())
               (apply-reflection-damage! [_ _ _ _ _ _ _] ()))]
    (reset! ps/player-states {})
    (try
      (ps/set-player-state! attacker {:ability-data attacker-ad})
      (binding [entity-damage/*entity-damage* stub]
        (with-redefs [rand (fn [] 1.0)
                      skill-effects/add-skill-exp! (fn [& _] (is false "no exp on non-crit"))
                      ach-dispatcher/trigger-custom-event! (fn [& _] (is false "no achievement event on non-crit"))]
          (let [result (h/deal-magic-damage! attacker "w" "victim" 10.0)]
            (is (= false (:critical? result)))
            (is (nil? (:crit-level result)))
            (is (= 1.0 (double (:crit-rate result))))
            (is (= 10.0 (double (:damage-before result))))
            (is (= 10.0 (double (:damage-after result))))
            (is (= true (:applied? result)))
            (is (= [] (:events result))))))
      (is (= 10.0 (double @last-damage)))
      (finally
        (reset! ps/player-states {})))))
