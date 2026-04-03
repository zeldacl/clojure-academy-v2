(ns cn.li.ac.ability.client.effects.particles
  "Particle effect commands for ability system (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.event :as evt]))

;; Particle effect command structure
;; {:type :particle
;;  :particle-type keyword (e.g., :flame, :electric, :water)
;;  :x :y :z position
;;  :count int
;;  :speed double
;;  :offset-x :offset-y :offset-z double}

(defn make-skill-activation-particles
  "Create particle effect for skill activation."
  [player-pos skill-category]
  (let [particle-type (case skill-category
                       :electromaster :electric-spark
                       :teleporter :portal
                       :meltdowner :flame
                       :vecmanip :end-rod
                       :generic)]
    {:type :particle
     :particle-type particle-type
     :x (:x player-pos)
     :y (+ (:y player-pos) 1.0)
     :z (:z player-pos)
     :count 10
     :speed 0.1
     :offset-x 0.5
     :offset-y 0.5
     :offset-z 0.5}))

(defn make-cp-consumption-particles
  "Create particle effect for CP consumption."
  [player-pos amount]
  (when (> amount 10)
    {:type :particle
     :particle-type :enchant
     :x (:x player-pos)
     :y (+ (:y player-pos) 1.5)
     :z (:z player-pos)
     :count (int (/ amount 10))
     :speed 0.05
     :offset-x 0.3
     :offset-y 0.3
     :offset-z 0.3}))

(defn make-overload-particles
  "Create particle effect for overload state."
  [player-pos]
  {:type :particle
   :particle-type :angry-villager
   :x (:x player-pos)
   :y (+ (:y player-pos) 2.0)
   :z (:z player-pos)
   :count 5
   :speed 0.0
   :offset-x 0.5
   :offset-y 0.5
   :offset-z 0.5})

(defn make-level-up-particles
  "Create particle effect for level up."
  [player-pos]
  {:type :particle
   :particle-type :totem-of-undying
   :x (:x player-pos)
   :y (+ (:y player-pos) 1.0)
   :z (:z player-pos)
   :count 30
   :speed 0.2
   :offset-x 1.0
   :offset-y 1.0
   :offset-z 1.0})

;; Event-driven particle spawning
(defonce ^:private particle-queue (atom []))

(defn queue-particle-effect!
  "Queue a particle effect to be spawned."
  [particle-cmd]
  (swap! particle-queue conj particle-cmd))

(defn poll-particle-effects!
  "Poll and clear all queued particle effects. Called by forge layer."
  []
  (let [effects @particle-queue]
    (reset! particle-queue [])
    effects))

;; Event listeners for automatic particle spawning
(defn on-skill-activation
  "Handle skill activation event."
  [event]
  (when-let [player-pos (:player-pos event)]
    (when-let [category (:category event)]
      (queue-particle-effect! (make-skill-activation-particles player-pos category)))))

(defn on-overload
  "Handle overload event."
  [event]
  (when-let [player-pos (:player-pos event)]
    (queue-particle-effect! (make-overload-particles player-pos))))

(defn on-level-up
  "Handle level up event."
  [event]
  (when-let [player-pos (:player-pos event)]
    (queue-particle-effect! (make-level-up-particles player-pos))))

(defn init!
  "Initialize particle effect system."
  []
  ;; Register event listeners
  ;; Note: Event system integration would happen here
  nil)
