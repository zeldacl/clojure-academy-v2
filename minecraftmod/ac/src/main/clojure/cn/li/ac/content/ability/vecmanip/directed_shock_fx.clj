(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx
  "Client FX for Directed Shock: first-person hand prepare/punch animation."
  (:require [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private sound-id "my_mod:vecmanip.directed_shock")
(def ^:private prepare-duration-ms 150.0)
(def ^:private punch-duration-ms 300.0)
(def ^:private directed-shock-effect-id :directed-shock)

(defn default-directed-shock-fx-state
  []
  {:effect-state {}})

(defn directed-shock-fx-snapshot
  []
  (or (hand-effects/effect-state-snapshot directed-shock-effect-id)
      (default-directed-shock-fx-state)))

(defn reset-directed-shock-fx-for-test!
  []
  (hand-effects/reset-hand-effect-state-for-test!
    directed-shock-effect-id
    (default-directed-shock-fx-state))
  nil)

(defn clear-directed-shock-owner!
  [owner-key]
  (hand-effects/update-effect-state!
    directed-shock-effect-id
    (fn [state]
      (update (or state (default-directed-shock-fx-state))
              :effect-state
              dissoc
              owner-key)))
  nil)

(defn- now-ms [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Transform curves (timestamp-based animation)
;; ---------------------------------------------------------------------------

(defn- prepare-transform [progress]
  {:tx (hand-effects/sample-curve [[0.0 0.0] [1.0 -0.02]] progress)
   :ty (hand-effects/sample-curve [[0.0 0.0] [0.5 0.2] [1.0 0.4]] progress)
   :tz (hand-effects/sample-curve [[0.0 0.0] [1.0 -0.05]] progress)
   :rot-x (hand-effects/sample-curve [[0.0 0.0] [1.0 -20.0]] progress)
   :rot-y 0.0
   :rot-z 0.0})

(defn- punch-transform [progress]
  {:tx (hand-effects/sample-curve [[0.0 -0.04] [0.5 -0.04] [1.0 0.0]] progress)
   :ty (hand-effects/sample-curve [[0.0 0.8] [0.5 0.75] [1.0 0.0]] progress)
   :tz (hand-effects/sample-curve [[0.0 0.0] [0.3 -0.4] [1.0 0.0]] progress)
   :rot-x (hand-effects/sample-curve [[0.0 -40.0] [0.5 -45.0] [1.0 0.0]] progress)
   :rot-y (hand-effects/sample-curve [[0.0 0.0] [0.3 10.0] [1.0 0.0]] progress)
   :rot-z 0.0})

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue-state! [state payload]
  (let [state* (or state (default-directed-shock-fx-state))
        {:keys [mode performed? owner-key ctx-id channel source-player-id world-id]} payload
        owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (update state* :effect-state assoc owner-key*
              (merge base-meta {:stage :prepare :started-at (now-ms)}))

      :perform
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0})
        (update state* :effect-state assoc owner-key*
                (merge base-meta {:stage :punch :started-at (now-ms)})))

      :end
      (if performed?
        state*
        (update state* :effect-state dissoc owner-key*))

      state*)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick-state! [state]
  (let [state* (or state (default-directed-shock-fx-state))]
    (update state* :effect-state
            (fn [states]
              (into {}
                    (remove (fn [[_ {:keys [stage started-at]}]]
                              (and (= stage :punch)
                                   (>= (- (now-ms) (long started-at)) punch-duration-ms))))
                    states)))))

;; ---------------------------------------------------------------------------
;; Transform
;; ---------------------------------------------------------------------------

(defn- transform []
  (when-let [[owner-key {:keys [stage started-at]}]
             (some (fn [[owner-key st]]
                     (when (:stage st)
                       [owner-key st]))
                   (:effect-state (directed-shock-fx-snapshot)))]
    (let [elapsed (- (now-ms) (long started-at))]
      (case stage
        :prepare
        (prepare-transform (min 1.0 (/ elapsed prepare-duration-ms)))
        :punch
        (let [progress (/ elapsed punch-duration-ms)]
          (if (>= progress 1.0)
            (do
              (clear-directed-shock-owner! owner-key)
              nil)
            (punch-transform progress)))
        nil))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (hand-effects/register-hand-effect! directed-shock-effect-id
    {:initial-state (default-directed-shock-fx-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :transform-fn transform})
  (fx-registry/register-fx-channels!
    [:directed-shock/fx-start :directed-shock/fx-perform :directed-shock/fx-end]
    (fn [ctx-id channel payload]
      (let [owner-meta {:owner-key [:ctx ctx-id]
                        :ctx-id ctx-id
                        :channel channel}]
        (case channel
          :directed-shock/fx-start
          (hand-effects/enqueue-hand-effect! directed-shock-effect-id (merge owner-meta {:mode :start}))
          :directed-shock/fx-perform
          (hand-effects/enqueue-hand-effect! directed-shock-effect-id (merge owner-meta {:mode :perform}))
          :directed-shock/fx-end
          (hand-effects/enqueue-hand-effect!
            directed-shock-effect-id
            (merge owner-meta {:mode :end :performed? (boolean (:performed? payload))}))))))
  nil)
