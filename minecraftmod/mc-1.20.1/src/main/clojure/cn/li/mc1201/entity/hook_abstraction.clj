(ns cn.li.mc1201.entity.hook-abstraction
  "Platform-agnostic entity operation protocols.
   
   Defines abstract interfaces that entity hooks operate on, allowing:
   - Unit testing of entity hooks with mock implementations
   - Easy porting to new Minecraft versions
   - Clear separation of business logic from platform details
   
   Entity hooks receive IEntity instances instead of raw Minecraft objects,
   enabling hooks to be tested independently of platform-specific code."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Core Entity Protocol
;; ============================================================================

(defprotocol IEntity
  "Abstraction for Minecraft entity operations.
   
   Implementers should provide adapters from concrete Minecraft entity types
   (net.minecraft.world.entity.Entity, net.minecraft.entity.Entity, etc.)
   to this protocol, allowing business logic to remain platform-independent."
  
  ;; Position queries
  (get-pos [this]
    "Get entity position as {:x double :y double :z double}")
  
  (get-eye-pos [this]
    "Get entity eye position (head) as {:x double :y double :z double}")
  
  (get-feet-pos [this]
    "Get entity feet position as {:x double :y double :z double}")
  
  ;; Position manipulation
  (set-pos! [this x y z]
    "Teleport entity to x,y,z coordinates")
  
  (move-relative! [this dx dy dz]
    "Move entity by relative offset")
  
  ;; Velocity
  (get-velocity [this]
    "Get entity velocity as {:vx double :vy double :vz double}")
  
  (set-velocity! [this vx vy vz]
    "Set entity velocity")
  
  (apply-velocity-impulse! [this vx vy vz]
    "Add velocity impulse to current velocity")
  
  ;; Rotation
  (get-rotation [this]
    "Get entity rotation as {:yaw float :pitch float}")
  
  (set-rotation! [this yaw pitch]
    "Set entity rotation")
  
  ;; World/Level
  (get-level [this]
    "Get the level/world this entity is in. Returns level object (type depends on platform)")
  
  (is-in-water? [this]
    "Check if entity is in water")
  
  (is-on-ground? [this]
    "Check if entity is on solid ground")
  
  (is-wet? [this]
    "Check if entity is wet (rain)")
  
  ;; Entity type/identity
  (get-entity-type [this]
    "Get entity type as keyword (e.g., :minecraft/player, :minecraft/creeper)")
  
  (is-player? [this]
    "Check if this is a player entity")
  
  (is-living? [this]
    "Check if this is a living entity (LivingEntity)")
  
  ;; Basic properties
  (get-name [this]
    "Get entity name (UUID for non-players, name for players)")
  
  (get-uuid [this]
    "Get entity UUID")
  
  (get-tag [this]
    "Get entity custom name tag")
  
  (set-tag! [this name]
    "Set entity custom name tag"))

;; ============================================================================
;; Living Entity Protocol (extends IEntity)
;; ============================================================================

(defprotocol ILivingEntity
  "Protocol for living entities (players, mobs, animals).
   Extends IEntity with health, effects, and damage operations."
  
  ;; Health
  (get-health [this]
    "Get entity current health")
  
  (set-health! [this health]
    "Set entity health")
  
  (get-max-health [this]
    "Get entity maximum health")
  
  (hurt! [this damage source-keyword]
    "Damage entity")
  
  ;; Potion effects
  (add-effect! [this effect-type duration amplifier]
    "Add potion effect. Parameters:
       effect-type: keyword (:speed, :slowness, etc.)
       duration: ticks
       amplifier: effect level (0-based)")
  
  (remove-effect! [this effect-type]
    "Remove potion effect")
  
  (has-effect? [this effect-type]
    "Check if entity has potion effect")
  
  (get-effects [this]
    "Get all active potion effects, returns sequence of
       {:type :duration :amplifier}")
  
  ;; Flags and attributes
  (can-breathe-underwater? [this]
    "Check if entity can breathe underwater")
  
  (is-sprinting? [this]
    "Check if entity is sprinting")
  
  (set-sprinting! [this sprinting?]
    "Set sprinting state"))

;; ============================================================================
;; Player Protocol (extends ILivingEntity)
;; ============================================================================

(defprotocol IPlayer
  "Protocol for player-specific operations."
  
  ;; Identity
  (get-player-name [this]
    "Get player username")
  
  (get-player-uuid [this]
    "Get player UUID")
  
  ;; Gamemode
  (get-gamemode [this]
    "Get player gamemode: :survival, :creative, :adventure, :spectator")
  
  (set-gamemode! [this gamemode]
    "Set player gamemode")
  
  (is-creative? [this]
    "Check if player is in creative mode")
  
  (is-survival? [this]
    "Check if player is in survival mode")
  
  ;; Capabilities
  (can-fly? [this]
    "Check if player can fly (creative/admin)")
  
  (is-flying? [this]
    "Check if player is currently flying")
  
  (set-flying! [this flying?]
    "Set player flying state")
  
  ;; Level/Experience
  (get-experience [this]
    "Get player total experience")
  
  (get-experience-level [this]
    "Get player experience level")
  
  (add-experience! [this amount]
    "Add experience to player")
  
  ;; Inventory access
  (get-inventory [this]
    "Get player inventory (player can hold items)")
  
  ;; Dimension/World
  (get-spawn-dimension [this]
    "Get player spawn dimension")
  
  (get-spawn-pos [this]
    "Get player spawn position"))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn satisfies-entity-protocol?
  "Check if an object implements IEntity protocol"
  [obj]
  (satisfies? IEntity obj))

(defn satisfies-living-protocol?
  "Check if an object implements ILivingEntity protocol"
  [obj]
  (satisfies? ILivingEntity obj))

(defn satisfies-player-protocol?
  "Check if an object implements IPlayer protocol"
  [obj]
  (satisfies? IPlayer obj))

(defn safe-cast-living
  "Cast to ILivingEntity if possible, otherwise returns nil"
  [entity]
  (when (satisfies-living-protocol? entity)
    entity))

(defn safe-cast-player
  "Cast to IPlayer if possible, otherwise returns nil"
  [entity]
  (when (satisfies-player-protocol? entity)
    entity))
