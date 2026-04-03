(ns cn.li.ac.ability.client.effects.sounds
  "Sound effect commands for ability system (AC layer - no Minecraft imports).")

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

;; Sound queue
(defonce ^:private sound-queue (atom []))

(defn queue-sound-effect!
  "Queue a sound effect to be played."
  [sound-cmd]
  (swap! sound-queue conj sound-cmd))

(defn poll-sound-effects!
  "Poll and clear all queued sound effects. Called by forge layer."
  []
  (let [sounds @sound-queue]
    (reset! sound-queue [])
    sounds))

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
  "Initialize sound effect system."
  []
  ;; Register event listeners
  ;; Note: Event system integration would happen here
  nil)
