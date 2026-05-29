(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  "Client FX for JetEngine skill: speed lines + launch flash."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private jet-engine-effect-id :jet-engine)

(defn default-jet-engine-fx-runtime-state
  []
  {:fx-state {}})

(defn jet-engine-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot jet-engine-effect-id)
      (default-jet-engine-fx-runtime-state)))

(defn reset-jet-engine-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    jet-engine-effect-id
    (default-jet-engine-fx-runtime-state))
  nil)

(defn clear-jet-engine-owner!
  [owner-key]
  (level-effects/update-effect-state!
    jet-engine-effect-id
    (fn [store]
      (update (or store (default-jet-engine-fx-runtime-state)) :fx-state dissoc owner-key)))
  nil)

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
  (let [store* (or store (default-jet-engine-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id speed dx dy dz]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :launch
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id "my_mod:md.jet_engine" :volume 0.8 :pitch 1.0})
        (assoc-in store* [:fx-state owner-key*]
                  (merge base-meta {:ttl 20
                                    :speed (double (or speed 1.5))
                                    :dx (double (or dx 0.0))
                                    :dy (double (or dy 0.0))
                                    :dz (double (or dz 0.0))})))
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id "my_mod:md.jet_charge" :volume 0.4 :pitch 1.0})
        store*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-jet-engine-fx-runtime-state))]
    (update store* :fx-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (let [ttl (long (or (:ttl st) 0))]
                        (when (pos? ttl)
                          [owner-key (update st :ttl dec)]))))
              states)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (let [alpha (->> (vals (:fx-state (jet-engine-fx-snapshot)))
                   (map #(long (or (:ttl %) 0)))
                   (filter pos?)
                   (map #(int (* 200 (/ (double %) 20.0))))
                   (reduce max 0))]
    (when (pos? alpha)
      {:ops [{:type :screen-flash
              :r 200 :g 220 :b 255 :a (min 80 alpha)}]})))

(defn init!
  []
  (level-effects/register-level-effect! jet-engine-effect-id
    {:initial-state (default-jet-engine-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:jet-engine/fx-start :jet-engine/fx-launch :jet-engine/fx-charge-max]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :jet-engine/fx-start
          (level-effects/enqueue-level-effect! jet-engine-effect-id
            (merge meta-payload {:mode :start})
            {:ctx-id ctx-id :channel channel})
          :jet-engine/fx-launch
          (level-effects/enqueue-level-effect! jet-engine-effect-id
            (merge meta-payload
                   {:mode :launch
                    :speed (:speed payload)
                    :dx (:dx payload)
                    :dy (:dy payload)
                    :dz (:dz payload)})
            {:ctx-id ctx-id :channel channel})
          :jet-engine/fx-charge-max
          (client-sounds/queue-current-sound-effect!
            {:type :sound :sound-id "my_mod:md.jet_max" :volume 0.5 :pitch 1.2})
          nil))))
  nil)
