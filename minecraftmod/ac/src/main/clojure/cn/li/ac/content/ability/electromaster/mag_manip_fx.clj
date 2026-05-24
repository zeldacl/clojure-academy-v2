(ns cn.li.ac.content.ability.electromaster.mag-manip-fx
  "Client FX for Mag Manip hold/throw lifecycle."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private hold-loop-sound "my_mod:em.lf_loop")
(def ^:private perform-sound "my_mod:em.mag_manip")

(defonce ^:private fx-state*
  (atom {:active? false
         :focus nil
         :block-id nil
         :ticks 0}))

(defn current-state []
  @fx-state*)

(defn- reset-state! []
  (reset! fx-state* {:active? false
                     :focus nil
                     :block-id nil
                     :ticks 0}))

(defn- enqueue! [payload]
  (let [{:keys [mode focus block-id]} payload]
    (case mode
      :hold-start
      (do
        (reset! fx-state* {:active? true
                           :focus focus
                           :block-id block-id
                           :ticks 0})
        (client-sounds/queue-sound-effect!
         {:type :sound :sound-id hold-loop-sound :volume 0.5 :pitch 1.0}))

      :hold-loop
      (swap! fx-state*
             (fn [state]
               (-> state
                   (assoc :active? true)
                   (cond-> focus (assoc :focus focus))
                   (cond-> block-id (assoc :block-id block-id)))))

      :throw
      (do
        (swap! fx-state* assoc :active? false)
        (client-sounds/queue-sound-effect!
         {:type :sound :sound-id perform-sound :volume 0.9 :pitch 1.0}))

      :end
      (reset-state!)

      nil)))

(defn- tick! []
  (swap! fx-state*
         (fn [state]
           (if-not (:active? state)
             state
             (let [ticks (inc (long (or (:ticks state) 0)))]
               (when (zero? (mod ticks 12))
                 (client-sounds/queue-sound-effect!
                  {:type :sound :sound-id hold-loop-sound :volume 0.35 :pitch 1.0}))
               (assoc state :ticks ticks))))))

(defn- current-hand-transform []
  (when (:active? @fx-state*)
    (let [ticks (double (or (:ticks @fx-state*) 0))
          phase (* 0.22 ticks)
          y (+ 0.02 (* 0.01 (Math/sin phase)))]
      {:translate [0.0 y 0.0]})))

(defn- build-level-plan [_camera-pos _hand-center-pos _tick]
  nil)

(defn- on-fx-channel [_ctx-id channel payload]
  (let [mode (case channel
               :mag-manip/fx-hold (:mode payload)
               :mag-manip/fx-throw :throw
               :mag-manip/fx-end :end
               nil)]
    (when mode
      (let [effect-payload (assoc (or payload {}) :mode mode)]
        (hand-effects/enqueue-hand-effect! :mag-manip effect-payload)
        (level-effects/enqueue-level-effect! :mag-manip effect-payload)))))

(defn init! []
  (reset-state!)
  (hand-effects/register-hand-effect! :mag-manip
                                      {:enqueue-fn enqueue!
                                       :tick-fn tick!
                                       :transform-fn current-hand-transform})
  (level-effects/register-level-effect! :mag-manip
                                        {:enqueue-fn enqueue!
                                         :tick-fn tick!
                                         :build-plan-fn build-level-plan})
  (fx-registry/register-fx-channels! [:mag-manip/fx-hold
                                      :mag-manip/fx-throw
                                      :mag-manip/fx-end]
                                    on-fx-channel)
  nil)
