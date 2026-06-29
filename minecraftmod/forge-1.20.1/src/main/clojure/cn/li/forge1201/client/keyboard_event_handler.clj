(ns cn.li.forge1201.client.keyboard-event-handler
  "Forge InputEvent$Key handler - routes to AC keybinding system.
   
   Purpose: Forge-specific event handling that abstracts platform differences.
   Routes Forge keyboard events to the universal mcmod protocol."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.keyboard-input :as kb-proto]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.glfw-polling-core :as glfw-polling]
            [cn.li.forge1201.client.key-mapping-adapter :as key-mapping-adapter])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.client.event InputEvent$Key]
           [net.minecraft.client Minecraft]))

;; ===== Forge Event Handler Registration =====

(def ^:private ^:dynamic *event-handler-installed?* false)

(defn- get-current-player-uuid
  "Get the current player's UUID from client session"
  []
  (try
    (if-let [player (.player (Minecraft/getInstance))]
      (str (.getUUID player))
      nil)
    (catch Exception _
      nil)))

(defn- get-client-session-id
  "Get the session ID for the current client"
  []
  (try
    (client-session/client-session-id)
    (catch Exception _
      (java.util.UUID/randomUUID))))

(defn ^:private on-key-input
  "Handle Forge InputEvent$Key - called for each key press/release.
   
   This is where platform-specific event flow meets universal protocol.
   Forge events only cover :alternative scheme (KeyMappings).
   For :original scheme (hardcoded keys), see glfw_polling_core."
  [^InputEvent$Key _event]
  (try
    (let [player-uuid (get-current-player-uuid)
          session-id (get-client-session-id)
          context {:player-uuid player-uuid
                   :client-session-id session-id
                   :logical-side :client}]
      ;; Dispatch only consumed Forge KeyMappings from AC :alternative scheme.
      (doseq [[input-id key-mapping] (key-mapping-adapter/get-key-mappings-by-input-id)]
        (when (.consumeClick key-mapping)
          (kb-proto/emit-keyboard-input! input-id context))))
    
    (catch Exception e
      (log/warn e "Error in Forge key input handler"))))

(defn ^:private on-client-tick
  "Handle Forge client tick end - poll GLFW for :original scheme inputs."
  [^TickEvent$ClientTickEvent event]
  (try
    (when (= TickEvent$Phase/END (.phase event))
      (let [player-uuid (get-current-player-uuid)
            session-id (get-client-session-id)
            mc (Minecraft/getInstance)]
        (when (and player-uuid session-id mc)
          (glfw-polling/poll-all-inputs! mc player-uuid session-id))))
    (catch Exception e
      (log/warn e "Error in Forge client tick keyboard polling"))))

(defn install-forge-event-handler!
  "Register the Forge InputEvent$Key listener.
   
   Called during platform initialization after:
   1. SPI providers installed
   2. AC keybindings bootstrapped
   3. KeyMappings registered
   
   This connects Forge events to the universal protocol."
  []
  (try
    (when-not *event-handler-installed?*
      (.addListener MinecraftForge/EVENT_BUS
                    EventPriority/NORMAL
                    false
                    InputEvent$Key
                    (reify java.util.function.Consumer
                      (accept [_ evt]
                        (on-key-input evt))))

      (.addListener MinecraftForge/EVENT_BUS
                    EventPriority/NORMAL
                    false
                    TickEvent$ClientTickEvent
                    (reify java.util.function.Consumer
                      (accept [_ evt]
                        (on-client-tick evt))))
      
      (log/info "Forge keyboard event handler installed")
      (alter-var-root (var *event-handler-installed?*) (constantly true)))
    
    (catch Exception e
      (log/error e "Failed to install Forge keyboard event handler"))))
