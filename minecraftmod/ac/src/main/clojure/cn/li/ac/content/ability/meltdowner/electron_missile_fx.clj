(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx
  "Client FX for ElectronMissile: orbiting sparks + impact flash per fired ball."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private electron-missile-effect-id :electron-missile)

(defn default-electron-missile-fx-runtime-state
  []
  {:charge-state {}
   :beams {}
   :impacts {}})

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
      (-> (or store (default-electron-missile-fx-runtime-state))
          (update :charge-state dissoc owner-key)
          (update :beams dissoc owner-key)
          (update :impacts dissoc owner-key))))
  nil)

(defn- all-impacts
  []
  (mapcat val (:impacts (electron-missile-fx-snapshot))))

(defn- all-beams
  []
  (mapcat val (:beams (electron-missile-fx-snapshot))))

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
    (let [store* (or store (default-electron-missile-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
      {:keys [mode source-player-id world-id target-x target-y target-z ticks balls start end]} (or payload {})
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
        (assoc-in store* [:charge-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :balls 0})))
      :update
      (assoc-in store* [:charge-state owner-key*]
                (merge base-meta
                       {:active? true
                        :ticks (long (or ticks 0))
                        :balls (long (or balls 0))}))
      :fire
      (let [store** (cond
                      (and start end)
                      (update-in store* [:beams owner-key*] (fnil conj [])
                                 (merge base-meta
                                        {:start start
                                         :end end
                                         :ttl 10
                                         :max-ttl 10}))

                      (and (some? target-x) (some? target-y) (some? target-z))
                      (update-in store* [:impacts owner-key*] (fnil conj [])
                                 (merge base-meta
                                        {:target-x target-x
                                         :target-y target-y
                                         :target-z target-z
                                         :ttl 10
                                         :max-ttl 10}))

                      :else store*)]
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.em_fire" :volume 0.35 :pitch (+ 0.85 (rand 0.3))})
        store**)
      :end
      (-> store*
          (update :charge-state dissoc owner-key*)
          (update :beams dissoc owner-key*)
          (update :impacts dissoc owner-key*))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-electron-missile-fx-runtime-state))
        impacts (all-impacts)
        beams (all-beams)]
    (doseq [impact impacts]
      (when-let [tx (:target-x impact)]
        (client-particles/queue-particle-effect! (:queue-owner impact)
          {:type :particle :particle-type :electric-spark
           :x (+ (double tx) 0.5)
           :y (+ (double (:target-y impact)) 0.5)
           :z (+ (double (:target-z impact)) 0.5)
           :count 2 :speed 0.2
           :offset-x 0.25 :offset-y 0.25 :offset-z 0.25})))
    (doseq [beam beams]
      (when-let [end-pos (:end beam)]
        (client-particles/queue-particle-effect! (:queue-owner beam)
          {:type :particle :particle-type :electric-spark
           :x (double (or (:x end-pos) 0.0))
           :y (double (or (:y end-pos) 0.0))
           :z (double (or (:z end-pos) 0.0))
           :count 1 :speed 0.08
           :offset-x 0.15 :offset-y 0.15 :offset-z 0.15})))
    (-> store*
        (update :charge-state
                (fn [states]
                  (into {}
                        (keep (fn [[owner-key st]]
                                (when (:active? st)
                                  [owner-key st])))
                        states)))
        (update :beams
                (fn [by-owner]
                  (into {}
                        (keep (fn [[owner-key q]]
                                (let [live (->> q
                                                (map #(update % :ttl dec))
                                                (filter #(pos? (long (:ttl %))))
                                                vec)]
                                  (when (seq live)
                                    [owner-key live]))))
                        by-owner)))
        (update :impacts
                (fn [by-owner]
                  (into {}
                        (keep (fn [[owner-key q]]
                                (let [live (->> q
                                                (map #(update % :ttl dec))
                                                (filter #(pos? (long (:ttl %))))
                                                vec)]
                                  (when (seq live)
                                    [owner-key live]))))
                        by-owner))))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (let [{:keys [beams]} (electron-missile-fx-snapshot)
        ops (mapcat (fn [[_owner-key xs]]
                      (map (fn [{:keys [start end]}]
                             (ru/line-op start end {:r 170 :g 255 :b 190 :a 190}))
                           xs))
                    beams)]
    (when (seq ops)
      {:ops (vec ops)})))

(defn init!
  []
  (level-effects/register-level-effect! electron-missile-effect-id
    {:initial-state (default-electron-missile-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:electron-missile/fx-start :electron-missile/fx-update :electron-missile/fx-fire :electron-missile/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :electron-missile/fx-start
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload {:mode :start})
            {:ctx-id ctx-id :channel channel})
          :electron-missile/fx-update
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload
                   {:mode :update
                    :ticks (long (or (:ticks payload) 0))
                    :balls (long (or (:balls payload) 0))})
            {:ctx-id ctx-id :channel channel})
          :electron-missile/fx-fire
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload
                   {:mode :fire
                    :start (:start payload)
                    :end (:end payload)
                    :target-x (:target-x payload)
                    :target-y (:target-y payload)
                    :target-z (:target-z payload)})
            {:ctx-id ctx-id :channel channel})
          :electron-missile/fx-end
          (level-effects/enqueue-level-effect! electron-missile-effect-id
            (merge meta-payload {:mode :end})
            {:ctx-id ctx-id :channel channel})
          nil))))
  nil)
