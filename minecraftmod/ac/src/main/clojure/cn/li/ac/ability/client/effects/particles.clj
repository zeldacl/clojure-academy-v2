(ns cn.li.ac.ability.client.effects.particles
  "Particle effect commands for ability system (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

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

;; Event-driven particle spawning (session-scoped)
(defonce ^:private particle-queue (atom {}))

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Particle queue requires %s" label)
                    {:owner owner
                     :required label}))))

(defn- current-session-id
  [owner]
  (require-owner-value owner
                       ":client-session-id"
                       (or (when (map? owner) (:client-session-id owner))
                           (when (map? owner) (:session-id owner))
                           (:client-session-id runtime-hooks/*player-state-owner*)
                           runtime-hooks/*client-session-id*)))

(defn- normalize-session-id
  [owner-or-session]
  (cond
    (map? owner-or-session)
    (current-session-id owner-or-session)

    (and (vector? owner-or-session)
         (= 2 (count owner-or-session))
         (vector? (first owner-or-session)))
    (first owner-or-session)

    (some? owner-or-session)
    owner-or-session

    :else
    (current-session-id nil)))

(defn current-effect-owner
  []
  (or runtime-hooks/*player-state-owner*
      (when runtime-hooks/*client-session-id*
        {:client-session-id runtime-hooks/*client-session-id*})
      (throw (ex-info "Current particle effect owner requires :client-session-id"
                      {:required ":client-session-id"}))))

(defn queue-particle-effect!
  "Queue a particle effect to be spawned."
  ([particle-cmd]
   (queue-particle-effect! nil particle-cmd))
  ([owner-or-session particle-cmd]
   (swap! particle-queue update (normalize-session-id owner-or-session) (fnil conj []) particle-cmd)
   nil))

(defn queue-current-particle-effect!
  [particle-cmd]
  (queue-particle-effect! (current-effect-owner) particle-cmd))

(defn poll-particle-effects!
  "Poll and clear all queued particle effects. Called by forge layer."
  ([]
   (poll-particle-effects! nil))
  ([owner-or-session]
   (let [session-id (normalize-session-id owner-or-session)
         drained (atom [])]
     (swap! particle-queue
            (fn [queues]
              (reset! drained (vec (get queues session-id [])))
              (dissoc queues session-id)))
     @drained)))

(defn clear-session-particle-effects!
  ([]
   (clear-session-particle-effects! nil))
  ([owner-or-session]
   (swap! particle-queue dissoc (normalize-session-id owner-or-session))
   nil))

(defn clear-owner-particle-effects!
  [owner]
  (clear-session-particle-effects! owner))

(defn particle-queue-snapshot
  ([]
   @particle-queue)
  ([owner-or-session]
   (vec (get @particle-queue (normalize-session-id owner-or-session) []))))

(defn reset-particle-queue-for-test!
  ([]
   (reset-particle-queue-for-test! {}))
  ([queues]
   (reset! particle-queue
           (into {}
                 (map (fn [[session-id effects]]
                        [session-id (vec effects)]))
                 (or queues {})))
   nil))

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
  "Initialize particle effect system. Wire event listeners."
  []
  (evt/subscribe-ability-event!
   evt/EVT-ABILITY-ACTIVATE
   (fn [event] (on-skill-activation event)))
  (evt/subscribe-ability-event!
   evt/EVT-LEVEL-CHANGE
   (fn [event] (on-level-up event)))
  nil)
