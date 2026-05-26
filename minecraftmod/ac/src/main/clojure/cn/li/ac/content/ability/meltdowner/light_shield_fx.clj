(ns cn.li.ac.content.ability.meltdowner.light-shield-fx
  "Client FX for LightShield: glowing barrier effect."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom {}))

(defn light-shield-fx-snapshot []
  {:effect-state @effect-state})

(defn reset-light-shield-fx-for-test! []
  (reset! effect-state {})
  nil)

(defn clear-light-shield-owner! [owner-key]
  (swap! effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (swap! effect-state assoc owner-key* (merge base-meta {:active? true :ticks 0}))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.shield_on" :volume 0.7 :pitch 1.0}))
      :end
      (do
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.shield_off" :volume 0.5 :pitch 0.9})
        (clear-light-shield-owner! owner-key*))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [states]
           (into {}
                 (keep (fn [[owner-key st]]
                         (when (:active? st)
                           (let [ticks (inc (long (or (:ticks st) 0)))]
                             (when (zero? (mod ticks 5))
                               (client-particles/queue-particle-effect!
                                 {:type :particle :particle-type :end-rod
                                  :x 0.0 :y 1.0 :z 0.0
                                  :count 3 :speed 0.15
                                  :offset-x 0.8 :offset-y 0.8 :offset-z 0.8
                                  :relative-to-camera? true}))
                             [owner-key (assoc st :ticks ticks)]))))
                 states))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :light-shield
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:light-shield/fx-start :light-shield/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :light-shield/fx-start
        (level-effects/enqueue-level-effect! :light-shield (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :light-shield/fx-end
        (level-effects/enqueue-level-effect! :light-shield (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
