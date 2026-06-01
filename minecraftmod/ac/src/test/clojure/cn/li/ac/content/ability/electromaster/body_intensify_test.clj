(ns cn.li.ac.content.ability.electromaster.body-intensify-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-registry :as ctx-reg]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability :as ability-content]
            [cn.li.ac.content.ability.electromaster.body-intensify :as body-intensify]
            [cn.li.ac.content.ability.fx-helpers :as fx]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- potion-effects-stub
  [applied*]
  (reify potion-effects/IPotionEffects
    (apply-potion-effect! [_ player-uuid effect-type duration amplifier]
      (swap! applied* conj {:player-uuid player-uuid
                            :effect effect-type
                            :duration duration
                            :amplifier amplifier})
      true)
    (remove-potion-effect! [_ _ _] true)
    (has-potion-effect? [_ _ _] false)
    (clear-all-effects! [_ _] true)))

(deftest apply-body-intensify-buffs-includes-first-effect-test
  (let [apply-buffs! (var-get #'cn.li.ac.content.ability.electromaster.body-intensify/apply-body-intensify-buffs!)
        applied* (atom [])]
    (with-redefs [body-intensify/get-probability (fn [_] 1.0)
                  body-intensify/get-buff-time (fn [_ _] 100)
                  body-intensify/get-hunger-buff-time (fn [_] 60)
                  body-intensify/get-buff-level (fn [_] 1)
                  body-intensify/base-effects (fn [] [{:effect :speed :max-amplifier 3}
                                                      {:effect :jump-boost :max-amplifier 3}])
                  body-intensify/cfg-int (fn [_] 0)
                  shuffle identity
                  rand (fn [] 0.0)]
      (binding [potion-effects/*potion-effects* (potion-effects-stub applied*)]
        (apply-buffs! "player-a" 20 0.2)))
    (is (= :speed (:effect (first @applied*))))
    (is (= :hunger (:effect (last @applied*))))))

(deftest apply-body-intensify-buffs-with-empty-pool-still-applies-hunger-test
  (let [apply-buffs! (var-get #'cn.li.ac.content.ability.electromaster.body-intensify/apply-body-intensify-buffs!)
        applied* (atom [])]
    (with-redefs [body-intensify/get-probability (fn [_] 2.0)
                  body-intensify/get-buff-time (fn [_ _] 100)
                  body-intensify/get-hunger-buff-time (fn [_] 60)
                  body-intensify/get-buff-level (fn [_] 1)
                  body-intensify/base-effects (fn [] [])
                  body-intensify/cfg-int (fn [_] 0)
                  shuffle identity
                  rand (fn [] 0.0)]
      (binding [potion-effects/*potion-effects* (potion-effects-stub applied*)]
        (apply-buffs! "player-b" 20 0.2)))
    (is (= 1 (count @applied*)))
    (is (= :hunger (:effect (first @applied*))))))

(deftest up-action-requires-min-charge-before-performing-test
  (ability-content/init-ability-content!)
  (let [up-fn (get-in (skill-registry/get-skill :body-intensify) [:actions :up!])
        apply-calls* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])
        end-calls* (atom [])
        terminate-calls* (atom [])]
    (with-redefs [body-intensify/min-time (fn [] 10)
                  body-intensify/max-time (fn [] 40)
                  body-intensify/cfg-double (fn [_] 0.02)
                  skill-config/lerp-int (fn [_ _ _] 25)
                  body-intensify/apply-body-intensify-buffs! (fn [player-id charge-ticks exp]
                                                               (swap! apply-calls* conj [player-id charge-ticks exp]))
                  skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                 (swap! exp-calls* conj [player-id skill-id amount]))
                  skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                     (swap! cooldown-calls* conj [player-id skill-id ticks]))
                  fx/send-end! (fn [ctx-id channel payload]
                                 (swap! end-calls* conj [ctx-id channel payload]))
                  ctx-reg/update-context! (fn [& _] nil)
                  ctx-reg/terminate-context! (fn [ctx-id _]
                                           (swap! terminate-calls* conj ctx-id))]
      (up-fn {:player-id "p1" :ctx-id "ctx-low" :exp 0.5 :hold-ticks 9})
      (up-fn {:player-id "p1" :ctx-id "ctx-ok" :exp 0.5 :hold-ticks 10}))
    (is (= [["p1" 10 0.5]] @apply-calls*))
    (is (= [["p1" :body-intensify 0.02]] @exp-calls*))
    (is (= [["p1" :body-intensify 25]] @cooldown-calls*))
    (is (empty? @end-calls*))
    (is (empty? @terminate-calls*))))
