(ns cn.li.mcmod.platform.entity-motion
  "Minecraft-free relay for neutral non-player entity motion/lifecycle operations."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn available?
  []
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/get-adapter fw-atom :entity-motion))))

(defn set-velocity!
  [world-id entity-uuid x y z]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :set-velocity!
                            world-id entity-uuid x y z))))

(defn add-velocity!
  [world-id entity-uuid x y z]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :add-velocity!
                            world-id entity-uuid x y z))))

(defn set-position!
  [world-id entity-uuid x y z]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :set-position!
                            world-id entity-uuid x y z))))

(defn set-block-body-block-id!
  [world-id entity-uuid block-id]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :set-block-id!
                            world-id entity-uuid block-id))))

(defn set-block-body-place-when-collide!
  [world-id entity-uuid place-when-collide?]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :set-place-when-collide!
                            world-id entity-uuid
                            (boolean place-when-collide?)))))

(defn discard-entity!
  [world-id entity-uuid]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :discard-entity!
                            world-id entity-uuid))))

(defn set-projectile-damage!
  [world-id entity-uuid damage]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :set-projectile-damage!
                            world-id entity-uuid (double damage)))))

(defn add-tag!
  [world-id entity-uuid tag]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :add-tag!
                            world-id entity-uuid (str tag)))))

(defn entity-velocity
  [world-id entity-uuid]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :entity-motion :get-velocity
                           world-id entity-uuid)))

(defn entity-position
  [world-id entity-uuid]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :entity-motion :get-position
                           world-id entity-uuid)))

(defn power-creeper!
  "True if entity-uuid resolved to a creeper and it was flipped to powered
  (charged) — matches original EMDamageHelper.attack's creeper side effect."
  [world-id entity-uuid]
  (boolean
   (when-let [fw-atom (fw/fw-atom)]
     (platform/call-adapter fw-atom :entity-motion :power-creeper!
                            world-id entity-uuid))))
