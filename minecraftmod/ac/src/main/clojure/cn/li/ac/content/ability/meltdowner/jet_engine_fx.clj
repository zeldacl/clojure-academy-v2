(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  "Client FX for JetEngine skill: speed lines + launch flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom {}))

(defn jet-engine-fx-snapshot []
  {:fx-state @fx-state})

(defn reset-jet-engine-fx-for-test! []
  (reset! fx-state {})
  nil)

(defn clear-jet-engine-owner! [owner-key]
  (swap! fx-state dissoc owner-key)
  nil)

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case (:mode payload)
      :launch
      (do
        (swap! fx-state assoc owner-key*
               (merge base-meta {:ttl 20 :speed (:speed payload 1.5)
                                 :dx (:dx payload 0.0)
                                 :dy (:dy payload 0.0)
                                 :dz (:dz payload 0.0)}))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.jet_engine" :volume 0.8 :pitch 1.0}))
      :start
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:md.jet_charge" :volume 0.4 :pitch 1.0})
      nil)))

(defn- tick! []
  (swap! fx-state
         (fn [states]
           (into {}
                 (keep (fn [[owner-key st]]
                         (when (pos? (long (or (:ttl st) 0)))
                           [owner-key (update st :ttl dec)])))
                 states))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [alpha (->> (vals @fx-state)
                   (map #(long (or (:ttl %) 0)))
                   (filter pos?)
                   (map #(int (* 200 (/ (double %) 20.0))))
                   (reduce max 0))]
    (when (pos? alpha)
      {:ops [{:type :screen-flash
              :r 200 :g 220 :b 255 :a (min 80 alpha)}]})))

(defn init! []
  (level-effects/register-level-effect! :jet-engine
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:jet-engine/fx-start :jet-engine/fx-launch :jet-engine/fx-charge-max]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :jet-engine/fx-start
        (level-effects/enqueue-level-effect! :jet-engine (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :jet-engine/fx-launch
        (level-effects/enqueue-level-effect! :jet-engine
          (merge meta-payload
                 {:mode :launch
                  :speed (:speed payload)
                  :dx (:dx payload) :dy (:dy payload) :dz (:dz payload)})
          {:ctx-id ctx-id :channel channel})
        :jet-engine/fx-charge-max
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.jet_max" :volume 0.5 :pitch 1.2})
        nil))))
  nil)
