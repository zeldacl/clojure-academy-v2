(ns cn.li.mc1201.entity.hook-adapter
  "Adapter implementations converting Minecraft entities to abstract IEntity protocol.
   
   This module bridges between platform-specific Minecraft code and the abstract
   entity protocols, allowing hooks to work with platform-agnostic interfaces.
   
   Each platform (Forge, Fabric) will provide concrete implementations of these adapters
   for their respective Minecraft entity types."
  (:require [cn.li.mc1201.entity.hook-abstraction :as hooks]
            [cn.li.mcmod.util.log :as log]))

(defrecord MinecraftEntityAdapter
  [minecraft-entity platform-context]
  hooks/IEntity
  (get-pos [_]
    ((:get-pos platform-context) minecraft-entity))
  (get-eye-pos [_]
    ((:get-eye-pos platform-context) minecraft-entity))
  (get-feet-pos [_]
    ((:get-feet-pos platform-context) minecraft-entity))
  (set-pos! [_ x y z]
    ((:set-pos platform-context) minecraft-entity x y z))
  (move-relative! [_ dx dy dz]
    ((:move-relative platform-context) minecraft-entity dx dy dz))
  (get-velocity [_]
    ((:get-velocity platform-context) minecraft-entity))
  (set-velocity! [_ vx vy vz]
    ((:set-velocity platform-context) minecraft-entity vx vy vz))
  (apply-velocity-impulse! [_ vx vy vz]
    ((:apply-velocity-impulse platform-context) minecraft-entity vx vy vz))
  (get-rotation [_]
    ((:get-rotation platform-context) minecraft-entity))
  (set-rotation! [_ yaw pitch]
    ((:set-rotation platform-context) minecraft-entity yaw pitch))
  (get-level [_]
    ((:get-level platform-context) minecraft-entity))
  (is-in-water? [_]
    ((:is-in-water? platform-context) minecraft-entity))
  (is-on-ground? [_]
    ((:is-on-ground? platform-context) minecraft-entity))
  (is-wet? [_]
    ((:is-wet? platform-context) minecraft-entity))
  (get-entity-type [_]
    ((:get-entity-type platform-context) minecraft-entity))
  (is-player? [_]
    ((:is-player? platform-context) minecraft-entity))
  (is-living? [_]
    ((:is-living? platform-context) minecraft-entity))
  (get-name [_]
    ((:get-name platform-context) minecraft-entity))
  (get-uuid [_]
    ((:get-uuid platform-context) minecraft-entity))
  (get-tag [_]
    ((:get-tag platform-context) minecraft-entity))
  (set-tag! [_ name]
    ((:set-tag! platform-context) minecraft-entity name)))

(defrecord MinecraftLivingEntityAdapter
  [minecraft-entity platform-context]
  hooks/IEntity
  (get-pos [_] ((:get-pos platform-context) minecraft-entity))
  (get-eye-pos [_] ((:get-eye-pos platform-context) minecraft-entity))
  (get-feet-pos [_] ((:get-feet-pos platform-context) minecraft-entity))
  (set-pos! [_ x y z] ((:set-pos platform-context) minecraft-entity x y z))
  (move-relative! [_ dx dy dz] ((:move-relative platform-context) minecraft-entity dx dy dz))
  (get-velocity [_] ((:get-velocity platform-context) minecraft-entity))
  (set-velocity! [_ vx vy vz] ((:set-velocity platform-context) minecraft-entity vx vy vz))
  (apply-velocity-impulse! [_ vx vy vz] ((:apply-velocity-impulse platform-context) minecraft-entity vx vy vz))
  (get-rotation [_] ((:get-rotation platform-context) minecraft-entity))
  (set-rotation! [_ yaw pitch] ((:set-rotation platform-context) minecraft-entity yaw pitch))
  (get-level [_] ((:get-level platform-context) minecraft-entity))
  (is-in-water? [_] ((:is-in-water? platform-context) minecraft-entity))
  (is-on-ground? [_] ((:is-on-ground? platform-context) minecraft-entity))
  (is-wet? [_] ((:is-wet? platform-context) minecraft-entity))
  (get-entity-type [_] ((:get-entity-type platform-context) minecraft-entity))
  (is-player? [_] ((:is-player? platform-context) minecraft-entity))
  (is-living? [_] ((:is-living? platform-context) minecraft-entity))
  (get-name [_] ((:get-name platform-context) minecraft-entity))
  (get-uuid [_] ((:get-uuid platform-context) minecraft-entity))
  (get-tag [_] ((:get-tag platform-context) minecraft-entity))
  (set-tag! [_ name] ((:set-tag! platform-context) minecraft-entity name))

  hooks/ILivingEntity
  (get-health [_] ((:get-health platform-context) minecraft-entity))
  (set-health! [_ health] ((:set-health! platform-context) minecraft-entity health))
  (get-max-health [_] ((:get-max-health platform-context) minecraft-entity))
  (hurt! [_ damage source-keyword] ((:hurt! platform-context) minecraft-entity damage source-keyword))
  (add-effect! [_ effect-type duration amplifier] ((:add-effect! platform-context) minecraft-entity effect-type duration amplifier))
  (remove-effect! [_ effect-type] ((:remove-effect! platform-context) minecraft-entity effect-type))
  (has-effect? [_ effect-type] ((:has-effect? platform-context) minecraft-entity effect-type))
  (get-effects [_] ((:get-effects platform-context) minecraft-entity))
  (can-breathe-underwater? [_] ((:can-breathe-underwater? platform-context) minecraft-entity))
  (is-sprinting? [_] ((:is-sprinting? platform-context) minecraft-entity))
  (set-sprinting! [_ sprinting?] ((:set-sprinting! platform-context) minecraft-entity sprinting?)))

(defrecord MinecraftPlayerAdapter
  [minecraft-player platform-context]
  hooks/IEntity
  (get-pos [_] ((:get-pos platform-context) minecraft-player))
  (get-eye-pos [_] ((:get-eye-pos platform-context) minecraft-player))
  (get-feet-pos [_] ((:get-feet-pos platform-context) minecraft-player))
  (set-pos! [_ x y z] ((:set-pos platform-context) minecraft-player x y z))
  (move-relative! [_ dx dy dz] ((:move-relative platform-context) minecraft-player dx dy dz))
  (get-velocity [_] ((:get-velocity platform-context) minecraft-player))
  (set-velocity! [_ vx vy vz] ((:set-velocity platform-context) minecraft-player vx vy vz))
  (apply-velocity-impulse! [_ vx vy vz] ((:apply-velocity-impulse platform-context) minecraft-player vx vy vz))
  (get-rotation [_] ((:get-rotation platform-context) minecraft-player))
  (set-rotation! [_ yaw pitch] ((:set-rotation platform-context) minecraft-player yaw pitch))
  (get-level [_] ((:get-level platform-context) minecraft-player))
  (is-in-water? [_] ((:is-in-water? platform-context) minecraft-player))
  (is-on-ground? [_] ((:is-on-ground? platform-context) minecraft-player))
  (is-wet? [_] ((:is-wet? platform-context) minecraft-player))
  (get-entity-type [_] ((:get-entity-type platform-context) minecraft-player))
  (is-player? [_] true)
  (is-living? [_] ((:is-living? platform-context) minecraft-player))
  (get-name [_] ((:get-name platform-context) minecraft-player))
  (get-uuid [_] ((:get-uuid platform-context) minecraft-player))
  (get-tag [_] ((:get-tag platform-context) minecraft-player))
  (set-tag! [_ name] ((:set-tag! platform-context) minecraft-player name))

  hooks/ILivingEntity
  (get-health [_] ((:get-health platform-context) minecraft-player))
  (set-health! [_ health] ((:set-health! platform-context) minecraft-player health))
  (get-max-health [_] ((:get-max-health platform-context) minecraft-player))
  (hurt! [_ damage source-keyword] ((:hurt! platform-context) minecraft-player damage source-keyword))
  (add-effect! [_ effect-type duration amplifier] ((:add-effect! platform-context) minecraft-player effect-type duration amplifier))
  (remove-effect! [_ effect-type] ((:remove-effect platform-context) minecraft-player effect-type))
  (has-effect? [_ effect-type] ((:has-effect? platform-context) minecraft-player effect-type))
  (get-effects [_] ((:get-effects platform-context) minecraft-player))
  (can-breathe-underwater? [_] ((:can-breathe-underwater? platform-context) minecraft-player))
  (is-sprinting? [_] ((:is-sprinting? platform-context) minecraft-player))
  (set-sprinting! [_ sprinting?] ((:set-sprinting! platform-context) minecraft-player sprinting?))

  hooks/IPlayer
  (get-player-name [_]
    ((:get-player-name platform-context) minecraft-player))
  (get-player-uuid [_]
    ((:get-player-uuid platform-context) minecraft-player))
  (get-gamemode [_]
    ((:get-gamemode platform-context) minecraft-player))
  (set-gamemode! [_ gamemode]
    ((:set-gamemode! platform-context) minecraft-player gamemode))
  (is-creative? [_]
    ((:is-creative? platform-context) minecraft-player))
  (is-survival? [_]
    ((:is-survival? platform-context) minecraft-player))
  (can-fly? [_]
    ((:can-fly? platform-context) minecraft-player))
  (is-flying? [_]
    ((:is-flying? platform-context) minecraft-player))
  (set-flying! [_ flying?]
    ((:set-flying! platform-context) minecraft-player flying?))
  (get-experience [_]
    ((:get-experience platform-context) minecraft-player))
  (get-experience-level [_]
    ((:get-experience-level platform-context) minecraft-player))
  (add-experience! [_ amount]
    ((:add-experience! platform-context) minecraft-player amount))
  (get-inventory [_]
    ((:get-inventory platform-context) minecraft-player))
  (get-spawn-dimension [_]
    ((:get-spawn-dimension platform-context) minecraft-player))
  (get-spawn-pos [_]
    ((:get-spawn-pos platform-context) minecraft-player)))

(defn adapt-entity
  "Convert Minecraft entity to IEntity adapter."
  [minecraft-entity platform-context]
  (->MinecraftEntityAdapter minecraft-entity platform-context))

(defn adapt-living-entity
  "Convert Minecraft living entity to ILivingEntity adapter."
  [minecraft-entity platform-context]
  (->MinecraftLivingEntityAdapter minecraft-entity platform-context))

(defn adapt-player
  "Convert Minecraft ServerPlayer to IPlayer adapter."
  [minecraft-player platform-context]
  (->MinecraftPlayerAdapter minecraft-player platform-context))
