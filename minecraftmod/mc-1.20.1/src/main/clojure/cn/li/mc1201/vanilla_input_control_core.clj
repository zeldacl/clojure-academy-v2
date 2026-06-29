(ns cn.li.mc1201.vanilla-input-control-core
  "Shared Vanilla input (LMB/RMB) suppression for both Forge and Fabric.
   
   Both platforms use the same net.minecraft.client.Options API internally.
   This core provides the business logic; platforms install via SPI."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.spi.vanilla-input-control])
  (:import (net.minecraft.client Minecraft Options)
           net.minecraft.client.KeyMapping))

(defn ^:private get-minecraft
  "Get the current Minecraft client instance (platform-independent)"
  []
  (Minecraft/getInstance))

(defn ^:private get-options
  "Get Options from Minecraft client (contains key binding state)"
  ([] (get-options (get-minecraft)))
  ([minecraft]
   (.options ^Minecraft minecraft)))

;; ===== Business Logic: When/How to Suppress =====

(defn attack-pressed?
  "Check if attack (LMB) is currently 'pressed' by checking KeyBinding state.
   
   Returns: boolean
   
   Used to decide if suppression is needed."
  ([] (attack-pressed? (get-minecraft)))
  ([minecraft]
   (try
     (let [opts (get-options minecraft)
           attack-key ^KeyMapping (.keyAttack opts)]
       (.isDown attack-key))
     (catch Exception e
       (log/debug e "Error checking attack key state")
       false))))

(defn use-pressed?
  "Check if use (RMB) is currently 'pressed'.
   
   Returns: boolean"
  ([] (use-pressed? (get-minecraft)))
  ([minecraft]
   (try
     (let [opts (get-options minecraft)
           use-key ^KeyMapping (.keyUse opts)]
       (.isDown use-key))
     (catch Exception e
       (log/debug e "Error checking use key state")
       false))))

(defn suppress-vanilla-attack-use!
  "Suppress Vanilla LMB/RMB input handling when AC ability is active.
   
   Implementation:
   - Set attack KeyBinding to 'not pressed'
   - Set use KeyBinding to 'not pressed'
   - Prevents Minecraft from processing these inputs in world
   - Player still holds physical LMB/RMB but MC doesn't see it
   
   Args:
   - minecraft: Minecraft client (or nil to get current)
   
   Timing: Called when AC ability becomes active
   
   Note: This is not a true input block. We're just setting KeyBinding state
   to neutral. The physical keys can still be queried via GLFW polling."
  ([] (suppress-vanilla-attack-use! (get-minecraft)))
  ([minecraft]
   (try
     (let [opts (get-options minecraft)
           attack-key ^KeyMapping (.keyAttack opts)
           use-key ^KeyMapping (.keyUse opts)]
       
       ;; Set to 'not pressed' to suppress Vanilla handling
       (.setDown attack-key false)
       (.setDown use-key false)
       
       (log/debug "Vanilla LMB/RMB suppressed"))
     (catch Exception e
       (log/warn e "Failed to suppress Vanilla input")))))

(defn restore-vanilla-input!
  "Restore normal Vanilla input handling when AC ability deactivates.
   
   Implementation:
   - Reset attack and use KeyBindings to their natural state
   - Minecraft will resume processing LMB/RMB normally
   
   Args:
   - minecraft: Minecraft client (or nil to get current)
   
   Timing: Called when AC ability deactivates
   
   Note: KeyBindings don't have a 'restore' mechanism. We rely on the fact that
   Minecraft's normal key polling in the tick will naturally reset these to
   their actual physical state once we stop suppressing them."
  ([] (restore-vanilla-input! (get-minecraft)))
  ([minecraft]
   (try
     ;; In practice, KeyBindings are re-polled every tick anyway,
     ;; so just resetting to false and letting natural polling resume is sufficient.
     ;; If AC handler pressed these keys, they'll become 'pressed' again in next tick.
     
     (let [opts (get-options minecraft)
           attack-key ^KeyMapping (.keyAttack opts)
           use-key ^KeyMapping (.keyUse opts)]
       
       ;; Explicitly reset to match current physical state
       ;; (This ensures no ghost presses from suppression)
       (.setDown attack-key false)
       (.setDown use-key false)
       
       (log/debug "Vanilla LMB/RMB restored"))
     (catch Exception e
       (log/warn e "Failed to restore Vanilla input")))))

;; ===== SPI Implementation =====

(defrecord VanillaInputControlImpl []
  cn.li.mcmod.spi.vanilla-input-control/VanillaInputSuppressor
  (suppress-vanilla-attack-use! [_this minecraft-client]
    (suppress-vanilla-attack-use! minecraft-client))
  (restore-vanilla-input! [_this minecraft-client]
    (restore-vanilla-input! minecraft-client)))

(def ^:private impl (VanillaInputControlImpl.))

(defn get-spi-implementation
  "Get the VanillaInputSuppressor SPI implementation.
   
   Returns: object implementing VanillaInputSuppressor protocol
   
   Called by Forge/Fabric to install the SPI:
   (install-suppressor! (get-spi-implementation))"
  []
  impl)
