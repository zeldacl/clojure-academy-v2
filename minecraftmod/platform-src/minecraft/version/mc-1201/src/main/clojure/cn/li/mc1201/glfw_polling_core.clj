(ns cn.li.mc1201.glfw-polling-core
  "Shared GLFW key polling implementation for all platforms.

   Eliminates 95% code duplication between Forge and Fabric.
   Both platforms use the same GLFW_KEY_* constants and polling.

   Usage by platform:
   - Forge: call poll-all-inputs! in client tick event (AFTER event parsing)
   - Fabric: call poll-all-inputs! in client tick event (primary input mechanism)"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.keyboard-input :as kb-proto]
            [cn.li.mcmod.spi.key-scheme-provider :as key-provider]))

;; GLFW key codes (shared constants)
(def GLFW_KEY_Z 90)
(def GLFW_KEY_X 88)
(def GLFW_KEY_R 82)
(def GLFW_KEY_V 86)
(def GLFW_KEY_LEFT_ALT 342)

(defn ^:private is-key-pressed?
  "Query key state through installed KeySchemeProvider SPI."
  [scheme-name key-code]
  (boolean (key-provider/query-key-down? scheme-name key-code)))

(defn ^:private should-trigger?
  "Debounce logic: only trigger once per actual key press.

   Prevents rapid-fire by tracking transition from :released to :pressed."
  [_input-id was-pressed now-pressed]
  (and now-pressed (not was-pressed)))

(def ^:private last-poll-time
  "Client-process-global debounce state (single local player per client
   process — no multiplayer owner-key concern on this side)."
  (atom {}))

(def ^:private key-down-time
  "Timestamp (System/currentTimeMillis) when the V key was last pressed.
   Upstream AcademyCraft ClientHandler: toggle fires on KEY RELEASE only if
   held < 300ms — longer holds are skill-slot activations ($3=V)."
  (atom 0))

(defn poll-all-inputs!
  "Poll hardcoded key inputs and emit keyboard events.

   Called once per tick by Forge/Fabric platform layer.

   For Forge: supplements the KeyMapping event system (handles edge cases).
   For Fabric: the PRIMARY input mechanism (Fabric has no KeyMapping events).

   Polling targets:
   - :content/cycle-selection (R key)
   - :content/toggle-primary-state (V key)

   Flow:
   1. Poll GLFW state for each input
   2. Detect press transition (released → pressed)
   3. Emit keyboard-input event via protocol

   Context provided to handlers:
   {:player-uuid 'current-player-uuid'
    :client-session-id 'session-id'
    :logical-side :client}

   opts:
   - :suppress-triggers? — true while a Screen (chat/GUI) is open. Physical key
     state is still recorded into the debounce map (so closing a screen with a
     key still held does NOT fire a stale released→pressed transition), but no
     input events are emitted — matching vanilla KeyMapping screen suppression."
  ([_minecraft-client player-uuid client-session-id]
   (poll-all-inputs! _minecraft-client player-uuid client-session-id nil))
  ([_minecraft-client player-uuid client-session-id {:keys [suppress-triggers?]}]
   (try
     ;; Cycle selection (R key)
     (let [key-code GLFW_KEY_R
           is-pressed (is-key-pressed? :original key-code)
           was-pressed (get @last-poll-time :cycle-selection false)]
       (when (and (not suppress-triggers?)
                  (should-trigger? :cycle-selection was-pressed is-pressed))
         (kb-proto/emit-keyboard-input!
           :content/cycle-selection
           {:player-uuid player-uuid
            :client-session-id client-session-id
            :logical-side :client}))
       (swap! last-poll-time assoc :cycle-selection is-pressed))

     ;; Toggle primary state (V key — mode switch)
     ;; Upstream AcademyCraft ClientHandler: toggle fires on KEY RELEASE
     ;; only if held < 300ms.  Holding V longer activates slot-3 skill,
     ;; reusing the same key without conflict.
     (let [key-code GLFW_KEY_V
           is-pressed (is-key-pressed? :original key-code)
           was-pressed (get @last-poll-time :toggle-primary-state false)]
       (when is-pressed
         (when-not was-pressed
           (reset! key-down-time (System/currentTimeMillis))))
       (when (and (not suppress-triggers?)
                  was-pressed (not is-pressed)  ;; transition pressed→released
                  (< (- (System/currentTimeMillis) @key-down-time) 300))
         (kb-proto/emit-keyboard-input!
           :content/toggle-primary-state
           {:player-uuid player-uuid
            :client-session-id client-session-id
            :logical-side :client}))
       (swap! last-poll-time assoc :toggle-primary-state is-pressed))

     ;; Toggle terminal (Left Alt) — Fabric's only dispatch path for this input
     ;; (Forge covers it via the :alternative-scheme KeyMapping event and does
     ;; not call poll-all-inputs!, so there is no double-fire).
     (let [key-code GLFW_KEY_LEFT_ALT
           is-pressed (is-key-pressed? :original key-code)
           was-pressed (get @last-poll-time :toggle-terminal false)]
       (when (and (not suppress-triggers?)
                  (should-trigger? :toggle-terminal was-pressed is-pressed))
         (kb-proto/emit-keyboard-input!
           :content/toggle-terminal
           {:player-uuid player-uuid
            :client-session-id client-session-id
            :logical-side :client}))
       (swap! last-poll-time assoc :toggle-terminal is-pressed))

     nil
     (catch Exception e
       (log/warn e "Error polling GLFW inputs")))))

(defn reset-polling-state!
  "Reset debounce state (for testing or platform restart)"
  []
  (reset! last-poll-time {})
  nil)
