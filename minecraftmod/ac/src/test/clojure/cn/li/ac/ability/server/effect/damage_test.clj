(ns cn.li.ac.ability.server.effect.damage-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.effects.damage :as damage]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.world-effects :as world-effects]))

(defn- recording-damage [calls]
  (reify entity-damage/IEntityDamage
    (apply-direct-damage! [_ world-id entity-uuid damage source-type]
      (swap! calls conj {:world-id world-id :uuid entity-uuid :damage damage :source source-type})
      true)
    (apply-aoe-damage! [_ _ _ _ _ _ _ _ _] [])
    (apply-reflection-damage! [_ _ _ _ _ _ _] [])))

(defn- recording-world [calls]
  (reify world-effects/IWorldEffects
    (spawn-lightning! [_ _ _ _ _] false)
    (create-explosion! [_ _ _ _ _ _ _] false)
    (find-entities-in-radius [_ _ _ _ _ r]
      (swap! calls conj {:radius r})
      [{:uuid "v1" :x 0.0 :y 0.0 :z 0.0}
       {:uuid "att" :x 1.0 :y 0.0 :z 0.0}
       {:uuid "v2" :x 10.0 :y 0.0 :z 0.0}])
    (find-blocks-in-radius [_ _ _ _ _ _ _] [])
    (play-sound! [_ _ _ _ _ _ _ _ _] false)))

(deftest damage-direct-applies-when-bound-test
  (let [calls (atom [])
        dmg (recording-damage calls)
        evt {:player-id "att" :world-id "overworld" :target :victim :victim "u1" :amount 7.0 :damage-type :magic}]
    (binding [entity-damage/*entity-damage* dmg]
      (is (= evt (damage/execute-damage-direct! evt {:target :victim :amount 7.0 :damage-type :magic}))))
    (is (= [{:world-id "overworld" :uuid "u1" :damage 7.0 :source :magic}] @calls))))

(deftest damage-direct-skips-when-unbound-test
  (let [evt {:player-id "p" :world-id "w" :target :t :t "u9" :amount 3.0}]
    (binding [entity-damage/*entity-damage* nil]
      (is (= evt (damage/execute-damage-direct! evt {:target :t :amount 3.0}))))))

(deftest damage-aoe-falloff-and-exclude-test
  (let [dcalls (atom [])
        wcalls (atom [])
        dmg (recording-damage dcalls)
        wfx (recording-world wcalls)
        evt {:player-id "att"
             :world-id "dim"
             :center {:x 0.0 :y 0.0 :z 0.0}
             :radius 10.0
             :amount 100.0
             :damage-type :generic}]
    (binding [entity-damage/*entity-damage* dmg
              world-effects/*world-effects* wfx]
      (damage/execute-damage-aoe! evt {:center :center
                   :radius 10.0
                   :amount 100.0
                   :damage-type :generic
                   :exclude ["v2"]}))
    (is (= 1 (count @dcalls)))
    (is (= "v1" (:uuid (first @dcalls))))
    (is (= 100.0 (:damage (first @dcalls))))))

