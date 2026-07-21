(ns cn.li.fabric1201.client.keyboard-init
  "Fabric client keyboard input initialization.

   Purpose: Bootstrap AC keybindings and install polling.
   Fabric has no native keyboard events, so we rely entirely on GLFW polling
   for both :alternative and :original scheme inputs."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.spi.key-scheme-provider :as key-provider]
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
;; Maps logical key queries to GLFW key codes for Fabric (which has no KeyMapping events).

(def ^:private slot-glfw-keys [90 88 67 86])   ;; Z, X, C, V → skill slots 0-3
(def ^:private movement-glfw-keys {:forward 87  ;; W
                                   :back 83     ;; S
                                   :left 65     ;; A
                                   :right 68})  ;; D
;; :primary = N (78), :secondary = M (77) — raw key identity only; which
;; screen each opens is decided in keybinds.clj/tick-keys!, matching upstream
;; AcademyCraft's KEY_EDIT_PRESET = N (ClientHandler.java). Upstream has no
;; binding on M at all; the rewrite-only skill-tree viewer (no upstream
;; equivalent — upstream reaches it only via the terminal app) uses M so it
;; doesn't collide with N's upstream-aligned meaning.
(def ^:private screen-glfw-keys {:primary 78 :secondary 77})

(defn- glfw-key-state-fn
  "key-state-fn callback for keybinds/tick-keys!. Returns boolean key state from GLFW."
  [[kind sub-key]]
  (let [key-code (case kind
                   :slot (nth slot-glfw-keys sub-key nil)
                   :movement (get movement-glfw-keys sub-key)
                   :screen (get screen-glfw-keys sub-key)
                   nil)]
    (when key-code
      (try
        (key-provider/query-key-down? :original key-code)
        (catch Throwable _ false)))))

(def ^:private no-key-down-fn
  "key-state-fn used while a Screen (chat/inventory/GUI) is open: report every
   key as released so typed characters never fire gameplay keybinds (e.g. 'm'
   while typing '/aim' in chat opening the preset editor) and held keys get a
   clean release transition instead of sticking."
  (constantly false))

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
               (if screen-open? no-key-down-fn glfw-key-state-fn)
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
