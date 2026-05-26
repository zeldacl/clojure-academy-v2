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

(defonce ^:private fx-state*
  (atom {:states {}
         :current-owner-key nil}))

(defn mag-manip-fx-snapshot []
  @fx-state*)

(defn reset-mag-manip-fx-for-test! []
  (reset! fx-state* {:states {}
                     :current-owner-key nil})
  nil)

(defn clear-mag-manip-owner!
  [owner-key]
  (swap! fx-state*
         (fn [store]
           (let [states (dissoc (:states store) owner-key)]
             {:states states
              :current-owner-key (when-not (= owner-key (:current-owner-key store))
                                   (:current-owner-key store))})))
  nil)

(defn current-state []
  (let [{:keys [states current-owner-key]} @fx-state*]
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
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :hold-start
      (do
        (swap! fx-state*
               (fn [store]
                 (-> store
                     (assoc-in [:states owner-key*]
                               (merge default-state base-meta
                                      {:active? true
                                       :focus focus
                                       :block-id block-id
                                       :ticks 0}))
                     (assoc :current-owner-key owner-key*))))
        (client-sounds/queue-sound-effect!
         {:type :sound :sound-id hold-loop-sound :volume 0.5 :pitch 1.0}))

      :hold-loop
      (swap! fx-state*
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
        (swap! fx-state* update-in [:states owner-key*]
               (fn [state]
                 (merge default-state state base-meta {:active? false})))
        (client-sounds/queue-sound-effect!
         {:type :sound :sound-id perform-sound :volume 0.9 :pitch 1.0}))

      :end
      (clear-mag-manip-owner! owner-key*)

      nil)))

(defn- tick! []
  (swap! fx-state*
         (fn [store]
           (update store :states
                   (fn [states]
                     (into {}
                           (map (fn [[owner-key state]]
                                  (if-not (:active? state)
                                    [owner-key state]
                                    (let [ticks (inc (long (or (:ticks state) 0)))]
                                      (when (zero? (mod ticks 12))
                                        (client-sounds/queue-sound-effect!
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
  (reset-state!)
  (hand-effects/register-hand-effect! :mag-manip
                                      {:enqueue-fn enqueue!
                                       :tick-fn tick!
                                       :transform-fn current-hand-transform})
  (level-effects/register-level-effect! :mag-manip
                                        {:enqueue-event-fn level-enqueue!
                                         :tick-fn level-tick!
                                         :build-plan-fn build-level-plan})
  (fx-registry/register-fx-channels! [:mag-manip/fx-hold
                                      :mag-manip/fx-throw
                                      :mag-manip/fx-end]
                                    on-fx-channel)
  nil)
