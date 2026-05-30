(ns cn.li.ac.content.ability.meltdowner.mine-ray-luck-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.content.ability.meltdowner.mine-ray-luck :as luck]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn- context-mocks
  [initial]
  (let [ctx* (atom initial)]
    {:ctx* ctx*
     :get-context (fn [_] @ctx*)
     :update-context! (fn [_ f & args]
                        (swap! ctx* #(apply f % args)))}))

(deftest mine-ray-luck-breaks-block-and-applies-extra-drop-test
  (let [{:keys [ctx* get-context update-context!]} 
        (context-mocks {:skill-state {:target-x 1 :target-y 64 :target-z 2 :countdown 0.9}})
        break-calls* (atom [])
        exp-calls* (atom [])]
    (with-redefs [ctx/get-context get-context
                  ctx/update-context! update-context!
                  ctx/ctx-send-to-client! (fn [& _] nil)
                  skill-effects/skill-exp (fn [& _] 0.0)
                  skill-effects/add-skill-exp! (fn [& args]
                                                 (swap! exp-calls* conj args)
                                                 nil)
                  skill-config/lerp-double (fn [_skill-id field-id _exp]
                                             (case field-id
                                               :targeting.range 16.0
                                               :mining.break-speed 1.0
                                               0.0))
                  skill-config/tunable-double (fn [_skill-id field-id]
                                                (case field-id
                                                  :progression.exp-block 0.002
                                                  0.0))
                  skill-config/tunable-int (fn [_skill-id field-id]
                                             (case field-id
                                               :effect.extra-drop-y-offset -999
                                               0))
                  skill-config/probability (fn [_skill-id _field-id] 1.0)
                  geom/world-id-of (fn [_] "w")
                  geom/eye-pos (fn [_] {:x 0.0 :y 64.0 :z 0.0})
                  bm/*block-manipulation* (reify bm/IBlockManipulation
                                            (break-block! [_ _ world-id x y z drop-items?]
                                              (swap! break-calls* conj [world-id x y z drop-items?])
                                              true)
                                            (set-block! [_ _ _ _ _ _] true)
                                            (get-block [_ _ _ _ _] "minecraft:stone")
                                            (get-block-hardness [_ _ _ _ _] 1.0)
                                            (can-break-block? [_ _ _ _ _ _] true)
                                            (find-blocks-in-line [_ _ _ _ _ _ _ _ _] [])
                                            (liquid-block? [_ _ _ _ _] false)
                                            (farmland-block? [_ _ _ _ _] false))]
      (binding [raycast/*raycast* (reify raycast/IRaycast
                                    (raycast-blocks [_ _ _ _ _ _ _ _ _]
                                      {:x 1 :y 64 :z 2})
                                    (raycast-entities [_ _ _ _ _ _ _ _ _] nil)
                                    (raycast-combined [_ _ _ _ _ _ _ _ _] nil)
                                    (get-player-look-vector [_ _] {:x 1.0 :y 0.0 :z 0.0})
                                    (raycast-from-player [_ _ _ _] nil))]
        (luck/mine-ray-luck-tick! {:player-id "p1" :ctx-id "ctx-1"})))
    (is (= 2 (count @break-calls*)))
    (is (= [["p1" :mine-ray-luck 0.002]] @exp-calls*))
    (is (= {:target-x nil :target-y nil :target-z nil :countdown 0.0}
           (:skill-state @ctx*)))))
