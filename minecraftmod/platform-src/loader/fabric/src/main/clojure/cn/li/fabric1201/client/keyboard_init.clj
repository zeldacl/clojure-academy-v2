(ns cn.li.fabric1201.client.keyboard-init
  "Fabric client keyboard input initialization.

   Purpose: Bootstrap AC keybindings and install polling.
   Fabric has no native keyboard events, so we rely entirely on GLFW polling
   for both :alternative and :original scheme inputs."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.glfw-polling-core :as glfw-polling]
            [cn.li.mc1201.client.session :as client-session])
  (:import [net.minecraft.client Minecraft]
           [net.fabricmc.fabric.api.client.event.lifecycle.v1 ClientTickEvents]))

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

;; ===== Key State Function for keybinds/tick-keys! =====
;; slot/movement/screen key maps + glfw-key-state-fn/no-key-down-fn now live
;; in cn.li.mc1201.glfw-polling-core, shared with Forge's runtime_bridge.clj —
;; a key remap only needs to change one place.

(defn- get-player-uuid-str
  "Get current player UUID as string for keybinds context."
  []
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (str (.getUUID player))))
    (catch Throwable _ nil)))

(defn- on-client-tick-end
  "Fabric ClientTickEvents$End handler - poll GLFW for all inputs.
   While any Screen is open, raw GLFW polling must not fire gameplay inputs —
   vanilla KeyMappings are suppressed by the screen, and so must we be."
  [minecraft]
  (try
    (let [player-uuid (get-current-player-uuid)
          session-id (get-client-session-id)]
      (when (and player-uuid session-id)
        (let [screen-open? (some? (.screen ^Minecraft minecraft))]
          ;; Poll one-shot inputs (R, V key presses) via glfw-polling-core
          (glfw-polling/poll-all-inputs! minecraft player-uuid session-id
                                         {:suppress-triggers? screen-open?})
          ;; Poll per-frame held keys (skill slots + movement + GUI) via keybinds
          ;; Needs client session ctx: keybinds owner resolution reads client-session-id.
          (client-session/with-current-client-session
            #(power-runtime/client-tick-keys!
               (if screen-open? glfw-polling/no-key-down-fn glfw-polling/glfw-key-state-fn)
               get-player-uuid-str)))))
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
    (install/process-once! ::keyboard-handler-installed
      #(do
         ;; Register end-of-tick listener
         (.register ClientTickEvents/END_CLIENT_TICK
           (reify java.util.function.Consumer
             (accept [_this minecraft]
               (on-client-tick-end minecraft))))

         (log/info "Fabric keyboard handler installed")))

    (catch Exception e
      (log/error e "Failed to install Fabric keyboard handler"))))
