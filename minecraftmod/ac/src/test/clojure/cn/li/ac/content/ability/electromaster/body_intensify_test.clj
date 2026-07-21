(ns cn.li.ac.content.ability.electromaster.body-intensify-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
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

(defn- seed-charge-context!
  "body-intensify-up! ignores the (never-populated-in-production) hold-ticks
  positional argument and instead self-tracks charge duration in
  :skill-state — so tests must seed a real registered context rather than
  passing :hold-ticks through cb/apply-invoke."
  [player-id ctx-id ticks]
  (let [owner {:logical-side :server :server-session-id :test-session :player-uuid player-id}]
    (ctx/with-context-owner owner
      (ctx/register-context!
       (assoc (ctx/new-server-context player-id :body-intensify ctx-id owner)
              :status ctx/STATUS-ALIVE))
      (ctx-skill/update-skill-state-root! ctx-id identity {:hold-ticks ticks}))))

(deftest up-action-requires-min-charge-before-performing-test
  (let [up-fn (get-in body-intensify/body-intensify [:actions :up!])
        applied* (atom [])
        exp-calls* (atom [])
        cooldown-calls* (atom [])
        fx-calls* (atom [])
        context-registry-val (ctx/snapshot-context-registry)]
    (try
      (ctx/reset-contexts-for-test!)
      (with-buff-config ["speed:3"]
        #(with-redefs [potion-effects/available? (constantly true)
                       potion-effects/apply-potion-effect!* (fn [& _] (swap! applied* conj :applied) true)
                       skill-effects/add-skill-exp! (fn [player-id skill-id amount]
                                                      (swap! exp-calls* conj [player-id skill-id amount]))
                       skill-effects/set-main-cooldown! (fn [player-id skill-id ticks]
                                                          (swap! cooldown-calls* conj [player-id skill-id ticks]))
                       fx/send! (fn [& args] (swap! fx-calls* conj args) nil)]
           (seed-charge-context! "p-low" "ctx-low" 9)
           (cb/apply-invoke up-fn :player-id "p-low" :ctx-id "ctx-low" :exp 0.5)
           (is (empty? @applied*) "below min charge does not apply buffs")
           (is (empty? @exp-calls*))
           (is (empty? @cooldown-calls*))

           (seed-charge-context! "p-ok" "ctx-ok" 10)
           (cb/apply-invoke up-fn :player-id "p-ok" :ctx-id "ctx-ok" :exp 0.5)
           (is (pos? (count @applied*)) "successful release applies buffs")
           (is (= [["p-ok" :body-intensify 0.02]] @exp-calls*))
           (is (= [["p-ok" :body-intensify 25]] @cooldown-calls*))
           (is (= 2 (count @fx-calls*)) "fx sent on both the miss and the success release")))
      (finally
        (ctx/reset-contexts-for-test! context-registry-val)))))
