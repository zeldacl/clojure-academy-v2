(ns cn.li.ac.content.ability.vecmanip.directed-shock-fx
  "Client FX for Directed Shock: first-person hand prepare/punch animation."
  (:require [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private sound-id "my_mod:vecmanip.directed_shock")
(def ^:private prepare-duration-ms 150.0)
(def ^:private punch-duration-ms 300.0)

(defonce ^:private effect-state (atom nil))

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

(defn- enqueue! [payload]
  (let [{:keys [mode performed?]} payload]
    (case mode
      :start
      (reset! effect-state {:stage :prepare :started-at (now-ms)})
      :perform
      (do
        (reset! effect-state {:stage :punch :started-at (now-ms)})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0}))
      :end
      (when-not performed?
        (reset! effect-state nil))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (when-let [{:keys [stage started-at]} @effect-state]
    (when (and (= stage :punch)
               (>= (- (now-ms) (long started-at)) punch-duration-ms))
      (reset! effect-state nil))))

;; ---------------------------------------------------------------------------
;; Transform
;; ---------------------------------------------------------------------------

(defn- transform []
  (when-let [{:keys [stage started-at]} @effect-state]
    (let [elapsed (- (now-ms) (long started-at))]
      (case stage
        :prepare
        (prepare-transform (min 1.0 (/ elapsed prepare-duration-ms)))
        :punch
        (let [progress (/ elapsed punch-duration-ms)]
          (if (>= progress 1.0)
            (do (reset! effect-state nil) nil)
            (punch-transform progress)))
        nil))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(hand-effects/register-hand-effect! :directed-shock
  {:enqueue-fn   enqueue!
   :tick-fn      tick!
   :transform-fn transform})

(fx-registry/register-fx-channels!
  [:directed-shock/fx-start :directed-shock/fx-perform :directed-shock/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :directed-shock/fx-start
      (hand-effects/enqueue-hand-effect! :directed-shock {:mode :start})
      :directed-shock/fx-perform
      (hand-effects/enqueue-hand-effect! :directed-shock {:mode :perform})
      :directed-shock/fx-end
      (hand-effects/enqueue-hand-effect! :directed-shock
        {:mode :end :performed? (boolean (:performed? payload))}))))
