(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx
  "Client FX for ElectronMissile: orbiting sparks + impact flash per fired ball."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-electron-missile-fx-runtime-state
  []
  {:impacts {}})

(defn create-electron-missile-fx-runtime
  ([]
   (create-electron-missile-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-electron-missile-fx-runtime-state))}}]
   {::runtime ::electron-missile-fx-runtime
    :state* state*}))

(def ^:dynamic *electron-missile-fx-runtime* nil)

(defonce ^:private installed-electron-missile-fx-runtime
  (create-electron-missile-fx-runtime))

(defn- electron-missile-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::electron-missile-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-electron-missile-fx-runtime
  [runtime f]
  (when-not (electron-missile-fx-runtime? runtime)
    (throw (ex-info "Expected electron missile FX runtime"
                    {:value runtime})))
  (binding [*electron-missile-fx-runtime* runtime]
    (f)))

(defmacro with-electron-missile-fx-runtime
  [runtime & body]
  `(call-with-electron-missile-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-electron-missile-fx-runtime
  []
  (or *electron-missile-fx-runtime*
      installed-electron-missile-fx-runtime))

(defn- electron-missile-fx-state-atom
  []
  (:state* (current-electron-missile-fx-runtime)))

(defn- electron-missile-fx-state-snapshot
  []
  @(electron-missile-fx-state-atom))

(defn- update-electron-missile-fx-state!
  [f & args]
  (apply swap! (electron-missile-fx-state-atom) f args))

(defn electron-missile-fx-snapshot []
  (electron-missile-fx-state-snapshot))

(defn reset-electron-missile-fx-for-test! []
  (reset! (electron-missile-fx-state-atom) (default-electron-missile-fx-runtime-state))
  nil)

(defn clear-electron-missile-owner! [owner-key]
  (update-electron-missile-fx-state! update :impacts dissoc owner-key)
  nil)

(defn- all-impacts []
  (mapcat val (:impacts (electron-missile-fx-state-snapshot))))

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case (:mode payload)
      :start
      (client-sounds/queue-sound-effect! (:queue-owner base-meta)
        {:type :sound :sound-id "my_mod:md.em_start" :volume 0.5 :pitch 1.0})
      :fire
      (do
        (update-electron-missile-fx-state!
          update :impacts update owner-key*
          (fn [q]
            (let [q2 (conj (vec q) (merge base-meta payload {:ttl 10}))]
              (if (> (count q2) 8)
                (subvec q2 (- (count q2) 8))
                q2))))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:md.em_fire" :volume 0.35 :pitch (+ 0.85 (rand 0.3))}))
      nil)))

(defn- tick! []
  (update-electron-missile-fx-state!
    update :impacts
    (fn [by-owner]
      (into {}
            (keep (fn [[owner-key q]]
                    (let [live (->> q
                                    (map (fn [b] (update b :ttl dec)))
                                    (filter (fn [b] (pos? (:ttl b))))
                                    vec)]
                      (when (seq live)
                        [owner-key live]))))
            by-owner)))
  (doseq [impact (all-impacts)]
    (when-let [tx (:target-x impact)]
      (client-particles/queue-particle-effect! (:queue-owner impact)
        {:type :particle :particle-type :electric-spark
         :x (+ (double tx) 0.5)
         :y (+ (double (:target-y impact)) 0.5)
         :z (+ (double (:target-z impact)) 0.5)
         :count 2 :speed 0.2
         :offset-x 0.25 :offset-y 0.25 :offset-z 0.25}))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

(defn init! []
  (level-effects/register-level-effect! :electron-missile
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:electron-missile/fx-start :electron-missile/fx-fire]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :electron-missile/fx-start
        (level-effects/enqueue-level-effect! :electron-missile (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :electron-missile/fx-fire
        (level-effects/enqueue-level-effect! :electron-missile
          (merge meta-payload
                 {:mode :fire
                  :target-x (:target-x payload)
                  :target-y (:target-y payload)
                  :target-z (:target-z payload)})
          {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
