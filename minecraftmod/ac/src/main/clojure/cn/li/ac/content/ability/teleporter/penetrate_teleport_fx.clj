(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  "Client FX for PenetrateTP: brief glow through wall."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

  (defn default-penetrate-teleport-fx-runtime-state
    []
    {:fx-state {}})

  (defn create-penetrate-teleport-fx-runtime
    ([] 
     (create-penetrate-teleport-fx-runtime {}))
    ([{:keys [state*]
       :or {state* (atom (default-penetrate-teleport-fx-runtime-state))}}]
     {::runtime ::penetrate-teleport-fx-runtime
      :state* state*}))

  (def ^:dynamic *penetrate-teleport-fx-runtime* nil)

  (defonce ^:private installed-penetrate-teleport-fx-runtime
    (create-penetrate-teleport-fx-runtime))

  (defn- penetrate-teleport-fx-runtime?
    [runtime]
    (and (map? runtime)
         (= ::penetrate-teleport-fx-runtime (::runtime runtime))
         (some? (:state* runtime))))

  (defn call-with-penetrate-teleport-fx-runtime
    [runtime f]
    (when-not (penetrate-teleport-fx-runtime? runtime)
      (throw (ex-info "Expected Penetrate Teleport FX runtime"
                      {:value runtime})))
    (binding [*penetrate-teleport-fx-runtime* runtime]
      (f)))

  (defmacro with-penetrate-teleport-fx-runtime
    [runtime & body]
    `(call-with-penetrate-teleport-fx-runtime ~runtime (fn [] ~@body)))

  (defn- current-penetrate-teleport-fx-runtime
    []
    (or *penetrate-teleport-fx-runtime*
        installed-penetrate-teleport-fx-runtime))

  (defn- penetrate-teleport-fx-state-atom
    []
    (:state* (current-penetrate-teleport-fx-runtime)))

  (defn- penetrate-teleport-fx-state-snapshot
    []
    @(penetrate-teleport-fx-state-atom))

  (defn- update-penetrate-teleport-fx-state!
    [f & args]
    (apply swap! (penetrate-teleport-fx-state-atom) f args))

(defn penetrate-teleport-fx-snapshot []
    (penetrate-teleport-fx-state-snapshot))

(defn reset-penetrate-teleport-fx-for-test! []
    (reset! (penetrate-teleport-fx-state-atom) (default-penetrate-teleport-fx-runtime-state))
  nil)

(defn clear-penetrate-teleport-owner! [owner-key]
    (update-penetrate-teleport-fx-state! update :fx-state dissoc owner-key)
  nil)

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
        (update-penetrate-teleport-fx-state!
          update :fx-state assoc owner-key* (merge base-meta {:ttl 0 :active? true :available? false}))

      :update
        (update-penetrate-teleport-fx-state!
          update :fx-state update owner-key*
          (fn [st]
            (let [base (merge base-meta (or st {:ttl 0 :active? true}))]
              (assoc base
                     :owner-key owner-key*
                     :ctx-id ctx-id
                     :channel channel
                     :source-player-id source-player-id
                     :world-id world-id
                     :active? true
                     :available? (boolean (:available? payload))
                     :distance (double (or (:distance payload) 0.0))
                     :x (:x payload)
                     :y (:y payload)
                     :z (:z payload)))))

    :perform
    (do
      (when-let [x (:x payload)]
        (client-particles/queue-particle-effect! (:queue-owner base-meta)
          {:type :particle :particle-type :enchantment-table
           :x (double x) :y (+ (double (:y payload)) 1.0) :z (double (:z payload))
           :count 12 :speed 0.06
           :offset-x 0.5 :offset-y 0.8 :offset-z 0.5}))
      (client-sounds/queue-sound-effect! (:queue-owner base-meta)
        {:type :sound :sound-id "my_mod:tp.penetrate_tp" :volume 0.65 :pitch 1.15}))
      :end
      (clear-penetrate-teleport-owner! owner-key*)
      nil)))

(defn- tick! []
    (update-penetrate-teleport-fx-state!
      update :fx-state
      (fn [states]
        (reduce-kv
          (fn [acc owner-key st]
            (let [next-st (update st :ttl inc)]
              (when (and (:active? next-st)
                         (:x next-st)
                         (zero? (mod (:ttl next-st) 3)))
                (client-particles/queue-particle-effect! (:queue-owner next-st)
                  {:type :particle
                   :particle-type (if (:available? next-st) :portal :smoke)
                   :x (double (:x next-st))
                   :y (+ (double (:y next-st)) 1.0)
                   :z (double (:z next-st))
                   :count (if (:available? next-st) 5 2)
                   :speed 0.02
                   :offset-x 0.35
                   :offset-y 0.6
                   :offset-z 0.35}))
              (assoc acc owner-key next-st)))
          {}
          states))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! :penetrate-teleport
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:penetrate-tp/fx-start :penetrate-tp/fx-update :penetrate-tp/fx-perform :penetrate-tp/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :penetrate-tp/fx-start
        (level-effects/enqueue-level-effect! :penetrate-teleport (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :penetrate-tp/fx-update
        (level-effects/enqueue-level-effect! :penetrate-teleport
          (merge meta-payload
                 {:mode :update
                  :distance (:distance payload)
                  :available? (:available? payload)
                  :x (:x payload) :y (:y payload) :z (:z payload)})
          {:ctx-id ctx-id :channel channel})
        :penetrate-tp/fx-perform
        (level-effects/enqueue-level-effect! :penetrate-teleport
          (merge meta-payload {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
          {:ctx-id ctx-id :channel channel})
        :penetrate-tp/fx-end
        (level-effects/enqueue-level-effect! :penetrate-teleport (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
