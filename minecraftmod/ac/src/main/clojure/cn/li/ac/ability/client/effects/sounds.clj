(ns cn.li.ac.ability.client.effects.sounds
  "Sound effect commands for ability system (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.event :as evt]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

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
(defonce ^:private sound-queue (atom {}))

(defn- require-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Sound queue requires %s" label)
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
      (throw (ex-info "Current sound effect owner requires :client-session-id"
                      {:required ":client-session-id"}))))

(defn queue-sound-effect!
  "Queue a sound effect to be played."
  ([sound-cmd]
   (queue-sound-effect! nil sound-cmd))
  ([owner-or-session sound-cmd]
   (swap! sound-queue update (normalize-session-id owner-or-session) (fnil conj []) sound-cmd)
   nil))

(defn queue-current-sound-effect!
  [sound-cmd]
  (queue-sound-effect! (current-effect-owner) sound-cmd))

(defn poll-sound-effects!
  "Poll and clear all queued sound effects. Called by forge layer."
  ([]
   (poll-sound-effects! nil))
  ([owner-or-session]
   (let [session-id (normalize-session-id owner-or-session)
         drained (atom [])]
     (swap! sound-queue
            (fn [queues]
              (reset! drained (vec (get queues session-id [])))
              (dissoc queues session-id)))
     @drained)))

(defn clear-session-sound-effects!
  ([]
   (clear-session-sound-effects! nil))
  ([owner-or-session]
   (swap! sound-queue dissoc (normalize-session-id owner-or-session))
   nil))

(defn clear-owner-sound-effects!
  [owner]
  (clear-session-sound-effects! owner))

(defn sound-queue-snapshot
  ([]
   @sound-queue)
  ([owner-or-session]
   (vec (get @sound-queue (normalize-session-id owner-or-session) []))))

(defn reset-sound-queue-for-test!
  ([]
   (reset-sound-queue-for-test! {}))
  ([queues]
   (reset! sound-queue
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
