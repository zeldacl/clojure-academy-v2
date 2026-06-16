(ns cn.li.ac.content.ability.electromaster.body-intensify-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.electromaster.body-intensify :as body-intensify]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def ^:private apply-buffs! #'body-intensify/apply-body-intensify-buffs!)

(defn- with-buff-config [effect-entries f]
  (with-redefs [skill-config/tunable-string-list (fn [_ _] effect-entries)
                skill-config/tunable-double (fn [_ field-id]
                                             (case field-id
                                               :effect.probability-offset-ticks 0.0
                                               :effect.probability-divisor 20.0
                                               :effect.hunger-multiplier 3.0
                                               :progression.exp-use 0.02
                                               0.0))
                skill-config/lerp-double (fn [_ field-id _]
                                           (case field-id
                                             :effect.duration-multiplier 5.0
                                             :cost.down.overload 100.0
                                             0.0))
                skill-config/tunable-int (fn [_ field-id]
                                           (case field-id
                                             :charge.min-ticks 10
                                             :charge.max-ticks 40
                                             :effect.hunger-amplifier 0
                                             0))
                skill-config/lerp-int (fn [_ _ _] 25)
                shuffle identity
                clojure.core/rand (fn [] 0.0)]
    (f)))

(deftest apply-body-intensify-buffs-includes-first-effect-test
  (let [applied* (atom [])]
    (with-buff-config ["speed:3" "jump-boost:3"]
      #(with-redefs [potion-effects/available? (constantly true)
                     potion-effects/apply-potion-effect!* (fn [player-uuid effect duration amplifier]
                                                            (swap! applied* conj {:player-uuid player-uuid
                                                                                  :effect effect
                                                                                  :duration duration
                                                                                  :amplifier amplifier})
                                                            true)]
         (@apply-buffs! "player-a" 20 0.2)
         (is (= :speed (:effect (first @applied*))))
         (is (= :hunger (:effect (last @applied*))))))))

(deftest apply-body-intensify-buffs-with-empty-pool-still-applies-hunger-test
  (let [applied* (atom [])]
    (with-buff-config []
      #(with-redefs [potion-effects/available? (constantly true)
                     potion-effects/apply-potion-effect!* (fn [player-uuid effect duration amplifier]
                                                            (swap! applied* conj {:player-uuid player-uuid
                                                                                  :effect effect
                                                                                  :duration duration
                                                                                  :amplifier amplifier})
                                                            true)]
         (@apply-buffs! "player-b" 20 0.2)
         (is (= 1 (count @applied*)))
         (is (= :hunger (:effect (first @applied*))))))))

(deftest up-action-requires-min-charge-before-performing-test
  (let [up-fn (get-in body-intensify/body-intensify [:actions :up!])
        applied* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])]
    (with-buff-config ["speed:3"]
      #(with-redefs [potion-effects/available? (constantly true)
                     potion-effects/apply-potion-effect!* (fn [& _] (swap! applied* conj :applied) true)
                     skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                    (swap! exp-calls* conj [player-id skill-id amount]))
                     skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                        (swap! cooldown-calls* conj [player-id skill-id ticks]))]
         (up-fn {:player-id "p1" :ctx-id "ctx-low" :exp 0.5 :hold-ticks 9})
         (is (empty? @applied*) "below min charge does not apply buffs")
         (is (empty? @exp-calls*))
         (is (empty? @cooldown-calls*))
         (up-fn {:player-id "p1" :ctx-id "ctx-ok" :exp 0.5 :hold-ticks 10})
         (is (pos? (count @applied*)) "successful release applies buffs")
         (is (= [["p1" :body-intensify 0.02]] @exp-calls*))
         (is (= [["p1" :body-intensify 25]] @cooldown-calls*))))))
