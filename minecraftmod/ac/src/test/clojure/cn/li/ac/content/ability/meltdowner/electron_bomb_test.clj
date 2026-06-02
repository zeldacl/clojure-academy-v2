(ns cn.li.ac.content.ability.meltdowner.electron-bomb-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.content.ability.meltdowner.electron-bomb :as electron-bomb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.entity :as entity]))

(defn- stub-lerp-double [_skill-id field-id _exp]
  (case field-id
    :combat.damage 12.5
    :cost.down.cp 210.0
    :cost.down.overload 100.0
    0.0))

(defn- stub-tunable-double [_skill-id field-id]
  (case field-id
    :progression.exp-hit 0.003
    0.0))

(deftest perform-schedules-delayed-settlement-and-spawn-fx-test
  (let [spawn-calls* (atom [])
        fx-calls* (atom [])
        scheduled* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  raycast/*raycast* :mock-raycast
                  raycast/get-player-look-vector (fn [_ _] {:x 0.0 :y 0.0 :z 1.0})
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx-calls* conj [ctx-id channel payload])
                                            nil)
                  delayed-projectiles/mdball-near-expire-delay (fn [] 15)
                  delayed-projectiles/schedule-electron-bomb-beam! (fn [task]
                                                                     (swap! scheduled* conj task)
                                                                     nil)]
      (electron-bomb/electron-bomb-perform!
        {:player-id "p1" :ctx-id "ctx-1" :player {:id "player-obj"}})
      (is (= [[{:id "player-obj"} "my_mod:entity_md_ball" 0.0]]
             @spawn-calls*))
      (is (= [["ctx-1"
               :electron-bomb/fx-spawn
               {:x 1.0 :y 64.0 :z 2.0
                :dx 0.0 :dy 0.0 :dz 1.0}]]
             @fx-calls*))
      (is (= [{:player-id "p1"
               :ctx-id "ctx-1"
               :world-id "w"
               :eye {:x 1.0 :y 64.0 :z 2.0}
               :look-dir {:x 0.0 :y 0.0 :z 1.0}
               :damage 12.5
               :exp-gain 0.003
               :delay-ticks 15}]
             @scheduled*)))))

(deftest perform-without-look-vector-is-noop-test
  (let [spawn-calls* (atom [])
        fx-calls* (atom [])
        scheduled* (atom [])]
    (with-redefs [skill-effects/skill-exp (fn [& _] 0.5)
                  skill-config/lerp-double stub-lerp-double
                  skill-config/tunable-double stub-tunable-double
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 1.0 :y 64.0 :z 2.0})
                  raycast/*raycast* :mock-raycast
                  raycast/get-player-look-vector (fn [& _] nil)
                  entity/player-spawn-entity-by-id! (fn [& args]
                                                      (swap! spawn-calls* conj args)
                                                      true)
                  ctx/ctx-send-to-client! (fn [ctx-id channel payload]
                                            (swap! fx-calls* conj [ctx-id channel payload])
                                            nil)
                  delayed-projectiles/mdball-near-expire-delay (fn [] 15)
                  delayed-projectiles/schedule-electron-bomb-beam! (fn [task]
                                                                     (swap! scheduled* conj task)
                                                                     nil)]
      (electron-bomb/electron-bomb-perform!
        {:player-id "p1" :ctx-id "ctx-1" :player {:id "player-obj"}})
      (is (empty? @spawn-calls*))
      (is (empty? @fx-calls*))
      (is (empty? @scheduled*)))))
