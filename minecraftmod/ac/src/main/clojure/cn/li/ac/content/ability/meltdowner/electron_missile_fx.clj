(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx
  "Client FX for ElectronMissile: orbiting sparks + impact flash per fired ball."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private electron-missile-effect-id :electron-missile)

(defn default-electron-missile-fx-runtime-state
  []
  {:impacts {}})

(defn electron-missile-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot electron-missile-effect-id)
      (default-electron-missile-fx-runtime-state)))

(defn reset-electron-missile-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    electron-missile-effect-id
    (default-electron-missile-fx-runtime-state))
  nil)

(defn clear-electron-missile-owner!
  [owner-key]
  (level-effects/update-effect-state!
    electron-missile-effect-id
    (fn [store]
      (update (or store (default-electron-missile-fx-runtime-state)) :impacts dissoc owner-key)))
  nil)

(defn- all-impacts
  []
  (mapcat val (:impacts (electron-missile-fx-snapshot))))

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
  (let [store* (or store (default-electron-missile-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id target-x target-y target-z]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.em_start" :volume 0.5 :pitch 1.0})
        store*)
      :fire
      (let [store** (update-in store* [:impacts owner-key*] (fnil conj [])
                               (merge base-meta
                                      {:target-x target-x
                                       :target-y target-y
                                       :target-z target-z
                                       :ttl 10
                                       :max-ttl 10}))]
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.em_fire" :volume 0.35 :pitch (+ 0.85 (rand 0.3))})
        store**)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-electron-missile-fx-runtime-state))
        impacts (all-impacts)]
    (doseq [impact impacts]
      (when-let [tx (:target-x impact)]
        (client-particles/queue-particle-effect! (:queue-owner impact)
          {:type :particle :particle-type :electric-spark
           :x (+ (double tx) 0.5)
           :y (+ (double (:target-y impact)) 0.5)
           :z (+ (double (:target-z impact)) 0.5)
           :count 2 :speed 0.2
           :offset-x 0.25 :offset-y 0.25 :offset-z 0.25})))
    (update store* :impacts
      (fn [by-owner]
        (into {}
              (keep (fn [[owner-key q]]
                      (let [live (->> q
                                      (map #(update % :ttl dec))
                                      (filter #(pos? (long (:ttl %))))
                                      vec)]
                        (when (seq live)
                          [owner-key live]))))
              by-owner)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  nil)

(defn init!
  []
  (level-effects/register-level-effect! electron-missile-effect-id
    {:initial-state (default-electron-missile-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:electron-missile/fx-start :electron-missile/fx-fire]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :electron-missile/fx-start
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload {:mode :start})
            {:ctx-id ctx-id :channel channel})
          :electron-missile/fx-fire
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload
                   {:mode :fire
                    :target-x (:target-x payload)
                    :target-y (:target-y payload)
                    :target-z (:target-z payload)})
            {:ctx-id ctx-id :channel channel})
          nil))))
  nil)
