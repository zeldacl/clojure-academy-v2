(ns cn.li.ac.content.ability.meltdowner.jet-engine-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.jet-engine :as jet]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(defn- context-mocks [initial]
  (let [ctx* (atom initial)
        terminated* (atom [])
        messages* (atom [])]
    {:ctx* ctx*
     :terminated* terminated*
     :messages* messages*
     :get-context (fn [_] @ctx*)
     :update-context! (fn [_ f & args]
                        (apply swap! ctx* f args))
     :terminate-context! (fn [ctx-id terminate-fn]
                           (swap! terminated* conj [ctx-id terminate-fn])
                           nil)
     :send! (fn [ctx-id channel payload]
              (swap! messages* conj {:ctx-id ctx-id :channel channel :payload payload})
              nil)}))

(deftest jet-engine-down-enters-marking-phase-test
  (let [{:keys [ctx* get-context update-context! send!]} (context-mocks {})]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/ctx-send-to-client! send!
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})]
      (binding [raycast/*raycast* nil]
        (jet/jet-engine-down! {:ctx-id "ctx-1" :player-id "p1" :cost-ok? true}))
      (is (= :marking (get-in @ctx* [:skill-state :phase])))
      (is (= 0 (get-in @ctx* [:skill-state :hold-ticks]))))))

(deftest jet-engine-up-success-enters-triggering-and-settles-test
  (let [{:keys [ctx* get-context update-context! terminate-context! send! terminated*]} (context-mocks {:skill-state {:phase :marking
                                                                                                            :target-pos {:x 8.0 :y 65.0 :z 8.0}}})
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  ctx/ctx-send-to-client! send!
            skill-effects/skill-exp (fn [_ _] 0.0)
                  skill-config/lerp-double (fn [_ _ _] 10.0)
                  skill-config/lerp-int (fn [_ _ _] 60)
                  skill-config/tunable-double (fn [_ _] 0.004)
                  skill-effects/perform-resource! (fn [& _] {:success? true})
                  skill-effects/add-skill-exp! (fn [& args] (swap! exp-calls* conj args))
                  skill-effects/set-main-cooldown! (fn [& args] (swap! cooldown-calls* conj args))
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 1.0})]
      (binding [teleportation/*teleportation* nil]
        (jet/jet-engine-up! {:player-id "p1" :ctx-id "ctx-1"}))
      (is (= :triggering (get-in @ctx* [:skill-state :phase])))
      (is (seq @exp-calls*))
      (is (seq @cooldown-calls*))
      (is (empty? @terminated*)))))

(deftest jet-engine-up-failure-terminates-without-cooldown-test
  (let [{:keys [ctx* get-context update-context! terminate-context! send! terminated*]} (context-mocks {:skill-state {:phase :marking
                                                                                                            :target-pos {:x 8.0 :y 65.0 :z 8.0}}})
        cooldown-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  ctx/ctx-send-to-client! send!
            skill-effects/skill-exp (fn [_ _] 0.0)
                  skill-config/lerp-double (fn [_ _ _] 10.0)
                  skill-config/lerp-int (fn [_ _ _] 60)
                  skill-config/tunable-double (fn [_ _] 0.004)
                  skill-effects/perform-resource! (fn [& _] {:success? false})
                  skill-effects/set-main-cooldown! (fn [& args] (swap! cooldown-calls* conj args))
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 1.0})]
      (jet/jet-engine-up! {:player-id "p1" :ctx-id "ctx-1"})
      (is (seq @terminated*))
      (is (empty? @cooldown-calls*))
      (is (= :marking (get-in @ctx* [:skill-state :phase]))))))

(deftest jet-engine-triggering-hit-dedup-test
  (let [{:keys [ctx* get-context update-context! terminate-context! send!]} (context-mocks {:skill-state {:phase :triggering
                                                                                                            :start-pos {:x 0.0 :y 64.0 :z 0.0}
                                                                                                            :target-pos {:x 4.0 :y 64.0 :z 0.0}
                                                                                                            :last-pos {:x 0.0 :y 64.0 :z 0.0}
                                                                                                            :velocity {:x 0.5 :y 0.0 :z 0.0}
                                                                                                            :world-id "w"
                                                                                                            :trigger-ticks 0
                                                                                                            :hit-uuids #{}}})
        damage-calls* (atom [])
  teleport-calls* (atom [])
  marks* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/terminate-context! terminate-context!
                  ctx/ctx-send-to-client! send!
                  skill-effects/skill-exp (fn [_ _] 0.0)
                  md-damage/mark-target! (fn [player-id target-id fx-context]
                                           (swap! marks* conj [player-id target-id fx-context])
                                           true)]
      (binding [teleportation/*teleportation* (reify teleportation/ITeleportation
                                                (teleport-player! [_ _ _ x y z]
                                                  (swap! teleport-calls* conj [x y z])
                                                  true)
                                                (teleport-with-entities! [_ _ _ _ _ _ _]
                                                  {:success false :teleported-count 0})
                                                (reset-fall-damage! [_ _] true)
                                                (get-player-position [_ _] {:world-id "w" :x 0.0 :y 64.0 :z 0.0})
                                                (get-player-dimension [_ _] "w"))
                player-motion/*player-motion* (reify player-motion/IPlayerMotion
                                                (set-velocity! [_ _ _ _ _] true)
                                                (add-velocity! [_ _ _ _ _] true)
                                                (get-velocity [_ _] {:x 0.0 :y 0.0 :z 0.0})
                                                (set-on-ground! [_ _ _] true)
                                                (is-on-ground? [_ _] false)
                                                (dismount-riding! [_ _] true))
                raycast/*raycast* (reify raycast/IRaycast
                                    (raycast-blocks [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-entities [_ _ _ _ _ _ _ _ _]
                                      {:uuid "target-1"})
                                    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
                                    (get-player-look-vector [_ _] {:x 1.0 :y 0.0 :z 0.0})
                                    (raycast-from-player [_ _ _ _] nil))
                entity-damage/*entity-damage* (reify entity-damage/IEntityDamage
                                                (apply-direct-damage! [_ _ entity-id _ _]
                                                  (swap! damage-calls* conj entity-id)
                                                  true)
                                                (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] [])
                                                (apply-reflection-damage! [_ _ _ _ _ _ _] []))]
        (jet/jet-engine-tick! {:player-id "p1" :ctx-id "ctx-1" :hold-ticks 1})
        (jet/jet-engine-tick! {:player-id "p1" :ctx-id "ctx-1" :hold-ticks 2})
        (is (= ["target-1"] @damage-calls*))
        (is (= #{"target-1"} (get-in @ctx* [:skill-state :hit-uuids])))
        (is (= 2 (count @teleport-calls*)))
        (is (= [["p1" "target-1" {:ctx-id "ctx-1"
                   :target-pos {:x nil :y nil :z nil}}]]
             @marks*))))))

