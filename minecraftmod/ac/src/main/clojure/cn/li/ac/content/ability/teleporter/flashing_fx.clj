(ns cn.li.ac.content.ability.teleporter.flashing-fx
  "Client FX for Flashing: movement preview + perform burst + cleanup."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-flashing-fx-runtime-state
  []
  {:fx-state {}})

(defn create-flashing-fx-runtime
  ([]
   (create-flashing-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-flashing-fx-runtime-state))}}]
   {::runtime ::flashing-fx-runtime
    :state* state*}))

(def ^:dynamic *flashing-fx-runtime* nil)

(defonce ^:private installed-flashing-fx-runtime
  (create-flashing-fx-runtime))

(defn- flashing-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::flashing-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-flashing-fx-runtime
  [runtime f]
  (when-not (flashing-fx-runtime? runtime)
    (throw (ex-info "Expected Flashing FX runtime"
                    {:value runtime})))
  (binding [*flashing-fx-runtime* runtime]
    (f)))

(defmacro with-flashing-fx-runtime
  [runtime & body]
  `(call-with-flashing-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-flashing-fx-runtime
  []
  (or *flashing-fx-runtime*
      installed-flashing-fx-runtime))

(defn- flashing-fx-state-atom
  []
  (:state* (current-flashing-fx-runtime)))

(defn- flashing-fx-state-snapshot
  []
  @(flashing-fx-state-atom))

(defn- update-flashing-fx-state!
  [f & args]
  (apply swap! (flashing-fx-state-atom) f args))

(defn flashing-fx-snapshot []
  (flashing-fx-state-snapshot))

(defn reset-flashing-fx-for-test! []
  (reset! (flashing-fx-state-atom) (default-flashing-fx-runtime-state))
  nil)

(defn clear-flashing-owner! [owner-key]
  (update-flashing-fx-state! update :fx-state dissoc owner-key)
  nil)

(defn- enqueue!
  [{:keys [payload ctx-id channel owner-key]}]
  (let [{:keys [mode source-player-id world-id]} payload
        owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :state-start
      (update-flashing-fx-state! update :fx-state assoc owner-key* (merge base-meta {:preview nil :burst []}))

      :preview-start
      (update-flashing-fx-state! update :fx-state update owner-key*
                                (fn [state]
                                  (assoc (merge base-meta (or state {:burst []}))
                                         :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))

      :preview-update
      (update-flashing-fx-state! update :fx-state update owner-key*
                                (fn [state]
                                  (assoc (merge base-meta (or state {:burst []}))
                                         :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))

      :preview-end
      (update-flashing-fx-state! update :fx-state update owner-key*
                                (fn [state]
                                  (assoc (merge base-meta (or state {:burst []})) :preview nil)))

      :perform
      (do
        (update-flashing-fx-state! update :fx-state update owner-key*
                                  (fn [state]
                                    (update (merge base-meta (or state {:preview nil :burst []})) :burst (fnil conj [])
                                            {:ttl 8
                                             :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
                                             :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}})))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:tp.tp_flashing" :volume 0.45 :pitch (+ 0.95 (rand 0.2))}))

      :state-end
      (clear-flashing-owner! owner-key*)

      nil)))

(defn- tick!
  []
  (update-flashing-fx-state!
    update :fx-state
    (fn [states]
      (reduce-kv
        (fn [acc owner-key state]
          (when-let [{:keys [x y z]} (:preview state)]
            (client-particles/queue-particle-effect! (:queue-owner state)
              {:type :particle :particle-type :portal
               :x (double x) :y (double y) :z (double z)
               :count 2 :speed 0.02
               :offset-x 0.2 :offset-y 0.4 :offset-z 0.2}))
          (let [burst' (->> (or (:burst state) [])
                            (map (fn [b] (update b :ttl dec)))
                            (filter (fn [b] (pos? (long (:ttl b)))))
                            vec)]
            (doseq [b burst']
              (let [{fx :x fy :y fz :z} (:from b)
                    {tx :x ty :y tz :z} (:to b)]
                (client-particles/queue-particle-effect! (:queue-owner state)
                  {:type :particle :particle-type :portal
                   :x (double fx) :y (double fy) :z (double fz)
                   :count 4 :speed 0.05
                   :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})
                (client-particles/queue-particle-effect! (:queue-owner state)
                  {:type :particle :particle-type :portal
                   :x (double tx) :y (double ty) :z (double tz)
                   :count 4 :speed 0.05
                   :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})))
            (assoc acc owner-key (assoc state :burst burst'))))
        {}
        states))))

(defn- build-plan [_cp _hcp _tick]
  nil)

(defn init!
  []
  (level-effects/register-level-effect! :flashing
    {:enqueue-event-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:flashing/fx-state-start
     :flashing/fx-preview-start
     :flashing/fx-preview-update
     :flashing/fx-preview-end
     :flashing/fx-perform
     :flashing/fx-state-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :flashing/fx-state-start
          (level-effects/enqueue-level-effect! :flashing (merge meta-payload {:mode :state-start})
                                               {:ctx-id ctx-id :channel channel})

          :flashing/fx-preview-start
          (level-effects/enqueue-level-effect! :flashing
            (merge meta-payload
                   {:mode :preview-start
                    :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})
            {:ctx-id ctx-id :channel channel})

          :flashing/fx-preview-update
          (level-effects/enqueue-level-effect! :flashing
            (merge meta-payload
                   {:mode :preview-update
                    :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})
            {:ctx-id ctx-id :channel channel})

          :flashing/fx-preview-end
          (level-effects/enqueue-level-effect! :flashing (merge meta-payload {:mode :preview-end})
                                               {:ctx-id ctx-id :channel channel})

          :flashing/fx-perform
          (level-effects/enqueue-level-effect! :flashing
            (merge meta-payload
                   {:mode :perform
                    :from-x (:from-x payload) :from-y (:from-y payload) :from-z (:from-z payload)
                    :to-x (:to-x payload) :to-y (:to-y payload) :to-z (:to-z payload)})
            {:ctx-id ctx-id :channel channel})

          :flashing/fx-state-end
          (level-effects/enqueue-level-effect! :flashing (merge meta-payload {:mode :state-end})
                                               {:ctx-id ctx-id :channel channel})

          nil))))
  nil)
