(ns cn.li.ac.ability.client.effects.sounds
  "Sound effect commands for ability system (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.client.effects.queue-infra :as queue-infra]))

;; Sound effect command structure
;; {:type :sound
;;  :sound-id string (e.g., "minecraft:entity.experience_orb.pickup")
;;  :volume float
;;  :pitch float
;;  :x :y :z position (optional, defaults to player position)}

(defn make-skill-activation-sound
  "Create sound effect for skill activation."
  [skill-category]
  (let [sound-id (case skill-category
                  :electromaster "minecraft:entity.lightning_bolt.thunder"
                  :teleporter "minecraft:entity.enderman.teleport"
                  :meltdowner "minecraft:entity.blaze.shoot"
                  :vecmanip "minecraft:block.beacon.activate"
                  "minecraft:entity.player.levelup")]
    {:type :sound
     :sound-id sound-id
     :volume 0.5
     :pitch 1.0}))

(defn make-cp-consumption-sound
  "Create sound effect for CP consumption."
  []
  {:type :sound
   :sound-id "minecraft:block.enchantment_table.use"
   :volume 0.3
   :pitch 1.5})

(defn make-overload-sound
  "Create sound effect for overload state."
  []
  {:type :sound
   :sound-id "minecraft:entity.generic.hurt"
   :volume 0.7
   :pitch 0.8})

(defn make-level-up-sound
  "Create sound effect for level up."
  []
  {:type :sound
   :sound-id "minecraft:entity.player.levelup"
   :volume 1.0
   :pitch 1.0})

(defn make-skill-learn-sound
  "Create sound effect for learning a skill."
  []
  {:type :sound
   :sound-id "minecraft:entity.experience_orb.pickup"
   :volume 0.8
   :pitch 1.2})

;; Sound queue (session-scoped)
(defn create-sound-queue-runtime
  []
  {::runtime ::sound-queue-runtime
   :queue* (atom {})})

(def ^:dynamic *sound-queue-runtime* nil)

(def ^:private _sound-queue-runtime (delay (create-sound-queue-runtime)))

(defn- sound-queue-runtime?
  [runtime]
  (and (map? runtime)
       (= ::sound-queue-runtime (::runtime runtime))
       (some? (:queue* runtime))))

(defn call-with-sound-queue-runtime
  [runtime f]
  (when-not (sound-queue-runtime? runtime)
    (throw (ex-info "Expected sound queue runtime"
                    {:runtime runtime})))
  (binding [*sound-queue-runtime* runtime]
    (f)))

(defmacro with-sound-queue-runtime
  [runtime & body]
  `(call-with-sound-queue-runtime ~runtime (fn [] ~@body)))

(defn- current-runtime
  []
  (or *sound-queue-runtime*
      @_sound-queue-runtime))

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
  (queue-infra/normalize-session-id "sound" owner-or-session))

(defn current-effect-owner
  []
  (queue-infra/current-effect-owner "sound"))

(defn queue-sound-effect!
  "Queue a sound effect to be played."
  ([sound-cmd]
   (queue-sound-effect! nil sound-cmd))
  ([owner-or-session sound-cmd]
  (queue-infra/queue-effect! (queue-atom) "sound" owner-or-session sound-cmd)
   nil))

(defn queue-current-sound-effect!
  [sound-cmd]
  (queue-sound-effect! (current-effect-owner) sound-cmd))

(defn poll-sound-effects!
  "Poll and clear all queued sound effects. Called by forge layer."
  ([]
   (poll-sound-effects! nil))
  ([owner-or-session]
   (queue-infra/poll-effects! (queue-atom) "sound" owner-or-session)))

(defn clear-session-sound-effects!
  ([]
   (clear-session-sound-effects! nil))
  ([owner-or-session]
  (update-queue! dissoc (normalize-session-id owner-or-session))
   nil))

(defn clear-owner-sound-effects!
  [owner]
  (clear-session-sound-effects! owner))

(defn sound-queue-snapshot
  ([]
  (queue-snapshot))
  ([owner-or-session]
  (vec (get (queue-snapshot) (normalize-session-id owner-or-session) []))))

(defn reset-sound-queue-for-test!
  ([]
   (reset-sound-queue-for-test! {}))
  ([queues]
   (reset! (queue-atom)
      (into {}
       (map (fn [[session-id sounds]]
         [session-id (vec sounds)]))
       (or queues {})))
   nil))

;; Event listeners for automatic sound playing
(defn on-skill-activation
  "Handle skill activation event."
  [event]
  (when-let [category (:category event)]
    (queue-sound-effect! (make-skill-activation-sound category))))

(defn on-overload
  "Handle overload event."
  [_event]
  (queue-sound-effect! (make-overload-sound)))

(defn on-level-up
  "Handle level up event."
  [_event]
  (queue-sound-effect! (make-level-up-sound)))

(defn on-skill-learn
  "Handle skill learn event."
  [_event]
  (queue-sound-effect! (make-skill-learn-sound)))

(defn init!
  "Initialize sound effect system. Wire event listeners."
  []
  (evt/subscribe-ability-event!
   evt/EVT-ABILITY-ACTIVATE
   (fn [event] (on-skill-activation event)))
  (evt/subscribe-ability-event!
   evt/EVT-LEVEL-CHANGE
   (fn [_event] (on-level-up _event)))
  (evt/subscribe-ability-event!
   evt/EVT-SKILL-LEARN
   (fn [_event] (on-skill-learn _event)))
  nil)
