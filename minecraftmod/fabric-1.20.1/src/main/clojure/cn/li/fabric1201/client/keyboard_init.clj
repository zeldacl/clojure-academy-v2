(ns cn.li.fabric1201.client.keyboard-init
  "Fabric client keyboard input initialization.
   
   Purpose: Bootstrap AC keybindings and install polling.
   Fabric has no native keyboard events, so we rely entirely on GLFW polling
   for both :alternative and :original scheme inputs."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mc1201.glfw-polling-core :as glfw-polling]
            [cn.li.mc1201.client.session :as client-session])
  (:import [net.minecraft.client Minecraft]
           [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents]))

(def ^:private ^:dynamic *keyboard-handler-installed?* false)

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

(defn- on-client-tick-end
  "Fabric ClientTickEvents$End handler - poll GLFW for all inputs."
  [minecraft]
  (try
    (let [player-uuid (get-current-player-uuid)
          session-id (get-client-session-id)]
      (when (and player-uuid session-id)
        ;; Poll all :original scheme inputs (GLFW-based)
        (glfw-polling/poll-all-inputs! minecraft player-uuid session-id)))
    (catch Exception e
      (log/warn e "Error polling Fabric keyboard inputs"))))

(defn install-keyboard-handler!
  "Install the Fabric client tick listener for GLFW polling.
   
   Called during platform initialization after:
   1. SPI providers installed
   2. AC keybindings bootstrapped
   3. KeyMappings registered (N/A for Fabric - Fabric doesn't support remapping)
   
   This polling handler is the primary input mechanism for Fabric."
  []
  (try
    (when-not *keyboard-handler-installed?*
      ;; Register end-of-tick listener
      (.register ClientTickEvents/END_CLIENT_TICK
        (reify java.util.function.Consumer
          (accept [_this minecraft]
            (on-client-tick-end minecraft))))
      
      (log/info "Fabric keyboard handler installed")
      (alter-var-root (var *keyboard-handler-installed?*) (constantly true)))
    
    (catch Exception e
      (log/error e "Failed to install Fabric keyboard handler"))))
