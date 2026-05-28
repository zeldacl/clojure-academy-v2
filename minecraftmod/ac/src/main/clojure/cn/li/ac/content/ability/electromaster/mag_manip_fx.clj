(ns cn.li.ac.content.ability.electromaster.mag-manip-fx
  "Client FX for Mag Manip hold/throw lifecycle."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private hold-loop-sound "my_mod:em.lf_loop")
(def ^:private perform-sound "my_mod:em.mag_manip")

(def ^:private default-state
  {:active? false
   :focus nil
   :block-id nil
   :ticks 0})

(defn default-mag-manip-fx-runtime-state
  []
  {:states {}
   :current-owner-key nil})

(defn create-mag-manip-fx-runtime
  ([]
   (create-mag-manip-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-mag-manip-fx-runtime-state))}}]
   {::runtime ::mag-manip-fx-runtime
    :state* state*}))

(def ^:dynamic *mag-manip-fx-runtime* nil)

(defn- mag-manip-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::mag-manip-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-mag-manip-fx-runtime
  [runtime f]
  (when-not (mag-manip-fx-runtime? runtime)
    (throw (ex-info "Expected mag manip FX runtime"
                    {:value runtime})))
  (binding [*mag-manip-fx-runtime* runtime]
    (f)))

(defmacro with-mag-manip-fx-runtime
  [runtime & body]
  `(call-with-mag-manip-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-mag-manip-fx-runtime
  []
  (or *mag-manip-fx-runtime*
      (throw (ex-info "Mag Manip FX runtime is not bound"
                      {:hint "Bind runtime via call-with-mag-manip-fx-runtime or use init! registered handlers"}))))

(defn- mag-manip-fx-state-atom
  []
  (:state* (current-mag-manip-fx-runtime)))

(defn- mag-manip-fx-state-snapshot
  []
  @(mag-manip-fx-state-atom))

(defn- update-mag-manip-fx-state!
  [f & args]
  (apply swap! (mag-manip-fx-state-atom) f args))

(defn mag-manip-fx-snapshot []
  (mag-manip-fx-state-snapshot))

(defn reset-mag-manip-fx-for-test! []
  (reset! (mag-manip-fx-state-atom) (default-mag-manip-fx-runtime-state))
  nil)

(defn clear-mag-manip-owner!
  [owner-key]
  (update-mag-manip-fx-state!
    (fn [store]
      (let [states (dissoc (:states store) owner-key)]
        {:states states
         :current-owner-key (when-not (= owner-key (:current-owner-key store))
                              (:current-owner-key store))})))
  nil)

(defn current-state []
  (let [{:keys [states current-owner-key]} (mag-manip-fx-state-snapshot)]
    (or (get states current-owner-key)
        (some (fn [[_ state]]
                (when (:active? state) state))
              states)
        default-state)))

(defn- reset-state! []
  (reset-mag-manip-fx-for-test!))

(defn- enqueue! [payload]
  (let [{:keys [mode focus block-id owner-key ctx-id channel source-player-id world-id]} payload
        owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :hold-start
      (do
        (update-mag-manip-fx-state!
          (fn [store]
            (-> store
                (assoc-in [:states owner-key*]
                          (merge default-state base-meta
                                 {:active? true
                                  :focus focus
                                  :block-id block-id
                                  :ticks 0}))
                (assoc :current-owner-key owner-key*))))
        (client-sounds/queue-current-sound-effect!
         {:type :sound :sound-id hold-loop-sound :volume 0.5 :pitch 1.0}))

      :hold-loop
      (update-mag-manip-fx-state!
        (fn [store]
          (-> store
              (update-in [:states owner-key*]
                         (fn [state]
                           (-> (merge default-state state base-meta)
                               (assoc :active? true)
                               (cond-> focus (assoc :focus focus))
                               (cond-> block-id (assoc :block-id block-id)))))
              (assoc :current-owner-key owner-key*))))

      :throw
      (do
        (update-mag-manip-fx-state! update-in [:states owner-key*]
                                    (fn [state]
                                      (merge default-state state base-meta {:active? false})))
        (client-sounds/queue-current-sound-effect!
         {:type :sound :sound-id perform-sound :volume 0.9 :pitch 1.0}))

      :end
      (clear-mag-manip-owner! owner-key*)

      nil)))

(defn- tick! []
  (update-mag-manip-fx-state!
    (fn [store]
      (update store :states
              (fn [states]
                (into {}
                      (map (fn [[owner-key state]]
                             (if-not (:active? state)
                               [owner-key state]
                               (let [ticks (inc (long (or (:ticks state) 0)))]
                                 (when (zero? (mod ticks 12))
                                   (client-sounds/queue-sound-effect! (:queue-owner state)
                                    {:type :sound :sound-id hold-loop-sound :volume 0.35 :pitch 1.0}))
                                 [owner-key (assoc state :ticks ticks)]))))
                      states))))))

(defn- current-hand-transform []
  (let [state (current-state)]
    (when (:active? state)
    (let [ticks (double (or (:ticks state) 0))
          phase (* 0.22 ticks)
          y (+ 0.02 (* 0.01 (Math/sin phase)))]
          {:translate [0.0 y 0.0]}))))

(defn- build-level-plan [_camera-pos _hand-center-pos _tick]
  nil)

(defn- level-enqueue! [_event] nil)

(defn- level-tick! [] nil)

(defn- on-fx-channel [ctx-id channel payload]
  (let [mode (case channel
               :mag-manip/fx-hold (:mode payload)
               :mag-manip/fx-throw :throw
               :mag-manip/fx-end :end
               nil)]
    (when mode
      (let [owner-meta {:owner-key [:ctx ctx-id]
                        :ctx-id ctx-id
                        :channel channel}
            effect-payload (merge owner-meta (assoc (or payload {}) :mode mode))]
        (hand-effects/enqueue-hand-effect! :mag-manip effect-payload)
        (level-effects/enqueue-level-effect! :mag-manip effect-payload
                                             {:ctx-id ctx-id :channel channel})))))

(defn init! []
  (let [runtime (create-mag-manip-fx-runtime)]
    (call-with-mag-manip-fx-runtime
      runtime
      reset-state!)
    (hand-effects/register-hand-effect! :mag-manip
                                        {:enqueue-fn (fn [payload]
                                                       (call-with-mag-manip-fx-runtime
                                                         runtime
                                                         (fn []
                                                           (enqueue! payload))))
                                         :tick-fn (fn []
                                                    (call-with-mag-manip-fx-runtime
                                                      runtime
                                                      tick!))
                                         :transform-fn (fn []
                                                         (call-with-mag-manip-fx-runtime
                                                           runtime
                                                           current-hand-transform))})
    (level-effects/register-level-effect! :mag-manip
                                          {:enqueue-event-fn level-enqueue!
                                           :tick-fn level-tick!
                                           :build-plan-fn build-level-plan}))
  (fx-registry/register-fx-channels! [:mag-manip/fx-hold
                                      :mag-manip/fx-throw
                                      :mag-manip/fx-end]
                                    on-fx-channel)
  nil)
