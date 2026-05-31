(ns cn.li.ac.ability.client.effects.particles
  "Particle effect commands for ability system (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.client.effects.queue-infra :as queue-infra]))

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
(defn create-particle-queue-runtime
  []
  {::runtime ::particle-queue-runtime
   :queue* (atom {})})

(def ^:dynamic *particle-queue-runtime* nil)

(defonce ^:private installed-particle-queue-runtime
  (create-particle-queue-runtime))

(defn- particle-queue-runtime?
  [runtime]
  (and (map? runtime)
       (= ::particle-queue-runtime (::runtime runtime))
       (some? (:queue* runtime))))

(defn call-with-particle-queue-runtime
  [runtime f]
  (when-not (particle-queue-runtime? runtime)
    (throw (ex-info "Expected particle queue runtime"
                    {:runtime runtime})))
  (binding [*particle-queue-runtime* runtime]
    (f)))

(defmacro with-particle-queue-runtime
  [runtime & body]
  `(call-with-particle-queue-runtime ~runtime (fn [] ~@body)))

(defn- current-runtime
  []
  (or *particle-queue-runtime*
      installed-particle-queue-runtime))

(defn- queue-atom
  []
  (:queue* (current-runtime)))

(defn- queue-snapshot
  []
  @(queue-atom))

(defn- update-queue!
  [f & args]
  (apply swap! (queue-atom) f args))

(defn- normalize-session-id
  [owner-or-session]
  (queue-infra/normalize-session-id "particle" owner-or-session))

(defn current-effect-owner
  []
  (queue-infra/current-effect-owner "particle"))

(defn queue-particle-effect!
  "Queue a particle effect to be spawned."
  ([particle-cmd]
   (queue-particle-effect! nil particle-cmd))
  ([owner-or-session particle-cmd]
  (queue-infra/queue-effect! (queue-atom) "particle" owner-or-session particle-cmd)
   nil))

(defn queue-current-particle-effect!
  [particle-cmd]
  (queue-particle-effect! (current-effect-owner) particle-cmd))

(defn poll-particle-effects!
  "Poll and clear all queued particle effects. Called by forge layer."
  ([]
   (poll-particle-effects! nil))
  ([owner-or-session]
   (queue-infra/poll-effects! (queue-atom) "particle" owner-or-session)))

(defn clear-session-particle-effects!
  ([]
   (clear-session-particle-effects! nil))
  ([owner-or-session]
  (update-queue! dissoc (normalize-session-id owner-or-session))
   nil))

(defn clear-owner-particle-effects!
  [owner]
  (clear-session-particle-effects! owner))

(defn particle-queue-snapshot
  ([]
  (queue-snapshot))
  ([owner-or-session]
  (vec (get (queue-snapshot) (normalize-session-id owner-or-session) []))))

(defn reset-particle-queue-for-test!
  ([]
   (reset-particle-queue-for-test! {}))
  ([queues]
   (reset! (queue-atom)
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
