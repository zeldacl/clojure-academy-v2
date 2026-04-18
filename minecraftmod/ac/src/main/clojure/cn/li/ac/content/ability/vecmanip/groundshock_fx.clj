(ns cn.li.ac.content.ability.vecmanip.groundshock-fx
  "Client FX for Groundshock: level-effect particles + hand-effect animation."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private sound-id "my_mod:vecmanip.groundshock")

;; ---------------------------------------------------------------------------
;; Level effect: particle burst on perform
;; ---------------------------------------------------------------------------

(defonce ^:private level-state (atom nil))

(defn- level-enqueue! [payload]
  (let [{:keys [mode affected-blocks broken-blocks]} payload]
    (case mode
      :perform
      (do
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id sound-id :volume 2.0 :pitch 1.0})
        (doseq [{:keys [x y z block-id]} affected-blocks]
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :block-crack
             :block-id (or block-id "minecraft:stone")
             :x (+ (double x) 0.5) :y (+ (double y) 1.0) :z (+ (double z) 0.5)
             :count (+ 4 (rand-int 4)) :speed 0.2
             :offset-x 1.0 :offset-y 0.6 :offset-z 1.0})
          (when (< (rand) 0.5)
            (client-particles/queue-particle-effect!
              {:type :particle :particle-type :smoke
               :x (+ (double x) 0.5 (- (* (rand) 0.6) 0.3))
               :y (+ (double y) 1.0 (* (rand) 0.2))
               :z (+ (double z) 0.5 (- (* (rand) 0.6) 0.3))
               :count 1 :speed 0.04
               :offset-x 0.04 :offset-y 0.05 :offset-z 0.04})))
        nil)
      nil)))

(defn- level-tick! [] nil)

(defn- level-build-plan [_camera-pos _hand-center-pos _tick] nil)

(level-effects/register-level-effect! :groundshock
  {:enqueue-fn    level-enqueue!
   :tick-fn       level-tick!
   :build-plan-fn level-build-plan})

;; ---------------------------------------------------------------------------
;; Hand effect: first-person camera shake + punch animation
;; ---------------------------------------------------------------------------

(defonce ^:private hand-state (atom nil))
(def ^:private perform-step 3.4)
(def ^:private perform-ticks-count 4)

(defn- hand-enqueue! [payload]
  (let [{:keys [mode charge-ticks performed?]} payload]
    (case mode
      :start
      (reset! hand-state {:charge-ticks 0 :perform-ticks 0 :active? true})
      :update
      (swap! hand-state
             (fn [state]
               (let [prev (long (or (:charge-ticks state) 0))
                     next-val (long (max 0 (or charge-ticks 0)))]
                 (doseq [tick (range (inc prev) (inc next-val))]
                   (let [pitch-factor (cond
                                        (< tick 4) (/ tick 4.0)
                                        (<= tick 20) 1.0
                                        (<= tick 25) (- 1.0 (/ (- tick 20) 5.0))
                                        :else 0.0)]
                     (hand-effects/add-camera-pitch-delta! (* -0.2 pitch-factor))))
                 {:charge-ticks next-val
                  :perform-ticks (long (or (:perform-ticks state) 0))
                  :active? true})))
      :perform
      (swap! hand-state
             (fn [state]
               {:charge-ticks (long (or (:charge-ticks state) 0))
                :perform-ticks perform-ticks-count
                :active? false}))
      :end
      (when-not performed?
        (reset! hand-state nil))
      nil)))

(defn- hand-tick! []
  (swap! hand-state
         (fn [state]
           (when state
             (let [remaining (long (or (:perform-ticks state) 0))]
               (when (pos? remaining)
                 (hand-effects/add-camera-pitch-delta! perform-step))
               (if (> remaining 1)
                 (assoc state :perform-ticks (dec remaining))
                 (when (or (:active? state) (pos? remaining))
                   (assoc state :perform-ticks 0))))))))

(hand-effects/register-hand-effect! :groundshock
  {:enqueue-fn   hand-enqueue!
   :tick-fn      hand-tick!})

;; ---------------------------------------------------------------------------
;; FX channel registration
;; ---------------------------------------------------------------------------

(fx-registry/register-fx-channels!
  [:groundshock/fx-start :groundshock/fx-update :groundshock/fx-perform :groundshock/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :groundshock/fx-start
      (hand-effects/enqueue-hand-effect! :groundshock {:mode :start})
      :groundshock/fx-update
      (hand-effects/enqueue-hand-effect! :groundshock
        {:mode :update :charge-ticks (long (or (:charge-ticks payload) 0))})
      :groundshock/fx-perform
      (do
        (hand-effects/enqueue-hand-effect! :groundshock {:mode :perform})
        (level-effects/enqueue-level-effect! :groundshock
          {:mode :perform
           :affected-blocks (:affected-blocks payload)
           :broken-blocks (:broken-blocks payload)}))
      :groundshock/fx-end
      (hand-effects/enqueue-hand-effect! :groundshock
        {:mode :end :performed? (boolean (:performed? payload))}))))
