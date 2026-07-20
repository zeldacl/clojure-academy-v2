(ns cn.li.forge1201.client.keyboard-event-handler
  "Forge InputEvent$Key handler - routes to AC keybinding system.

   Purpose: Forge-specific event handling that abstracts platform differences.
   Routes Forge keyboard events to the universal mcmod protocol."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.keyboard-input :as kb-proto]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.forge1201.client.key-mapping-adapter :as key-mapping-adapter])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.client.event InputEvent$Key]
           [net.minecraft.client Minecraft]
           [net.minecraft.client KeyMapping]))

;; GLFW_KEY_V = 86 — cannot reference GLFW/GLFW_KEY_V at AOT compile time
;; (native libs are absent). Matching the shared constant in mc1201/glfw_polling_core.
(def ^:private ^:const v-key-code 86)
(def ^:private ^:const v-toggle-threshold-ms 300)

(def ^:private v-key-down-time-atom
  "Atom holding Long — timestamp when V was last pressed. Toggle fires on
   RELEASE only if held < 300ms. Matching upstream AcademyCraft
   ClientHandler.onKeyUp()."
  (atom 0))

;; ===== Forge Event Handler Registration =====

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
   For :original scheme (hardcoded keys), see glfw_polling_core.

   V key (toggle-primary-state) uses release-based timing: the toggle fires
   on RELEASE only when held < 300ms — matching upstream AcademyCraft
   ClientHandler.  Holding V longer activates slot-3 skill without toggle."
  [^InputEvent$Key event]
  (try
    (let [player-uuid (get-current-player-uuid)
          session-id (get-client-session-id)
          context {:player-uuid player-uuid
                   :client-session-id session-id
                   :logical-side :client}]
      ;; Track V press timestamp for release-based toggle
      (when (= v-key-code (.getKey event))
        (case (.getAction event)
          1 (reset! v-key-down-time-atom (System/currentTimeMillis))  ;; press
          0 (when (< (- (System/currentTimeMillis) @v-key-down-time-atom) v-toggle-threshold-ms)
              (kb-proto/emit-keyboard-input! :content/toggle-primary-state context))  ;; short release
          nil))
      ;; Dispatch other consumed Forge KeyMappings from AC :alternative scheme
      ;; (skip V — handled above with release-based timing).
      (doseq [[input-id ^KeyMapping key-mapping] (key-mapping-adapter/get-key-mappings-by-input-id)]
        (when (and (not= input-id :content/toggle-primary-state)
                   (.consumeClick ^KeyMapping key-mapping))
          (kb-proto/emit-keyboard-input! input-id context))))
    (catch Exception e
      (log/warn e "Error in Forge key input handler"))))

(defn ^:private on-client-tick
  "Handle Forge client tick end.
   NOTE: Forge KeyMapping events (InputEvent$Key) handle all :alternative scheme
   keys (Z, X, R, V, Left Alt). GLFW polling in this handler is reserved for
   future :original scheme keys (LMB/RMB) that have no KeyMapping event source.
   Currently, no :original scheme keys are registered, so poll-all-inputs! is skipped
   to avoid double-firing with KeyMapping events."
  [^TickEvent$ClientTickEvent event]
  (try
    (when (= TickEvent$Phase/END (.phase event))
      ;; Reserved for future :original scheme key polling via glfw-polling/poll-all-inputs!
      nil)
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
    (install/process-once! ::event-handler-installed
      #(do
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

         (log/info "Forge keyboard event handler installed")))

    (catch Exception e
      (log/error e "Failed to install Forge keyboard event handler"))))
