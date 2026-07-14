(ns cn.li.mc1201.glfw-polling-core
  "Shared GLFW key polling implementation for all platforms.
   
   Eliminates 95% code duplication between Forge and Fabric.
   Both platforms use the same GLFW_KEY_* constants and polling.
   
   Usage by platform:
   - Forge: call poll-all-inputs! in client tick event (AFTER event parsing)
   - Fabric: call poll-all-inputs! in client tick event"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.protocol.keyboard-input :as kb-proto]
            [cn.li.mcmod.spi.key-scheme-provider :as key-provider]))

;; GLFW key codes (shared constants)
(def GLFW_KEY_Z 90)
(def GLFW_KEY_X 88)
(def GLFW_KEY_R 82)

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

(defn poll-all-inputs!
  "Poll all :original scheme inputs and emit keyboard events.

   Called once per tick by Forge/Fabric platform layer.

   Polling targets:
   - :content/cycle-selection (R key)
   - Other :original scheme inputs added later

   Flow:
   1. Poll GLFW state for each :original input
   2. Detect press transition (released → pressed)
   3. Emit keyboard-input event via protocol

   Context provided to handlers:
   {:player-uuid 'current-player-uuid'
    :client-session-id 'session-id'
    :logical-side :client}

   Note: All :alternative scheme inputs are handled by Forge/Fabric KeyMapping
   events. This polling is ONLY for :original scheme (which includes
   hardcoded platform keys like LMB/RMB that have no event source)."
  [_minecraft-client player-uuid client-session-id]
  (try
    ;; Cycle selection (R key, :original scheme)
    (let [key-code GLFW_KEY_R
          is-pressed (is-key-pressed? :original key-code)
          was-pressed (get @last-poll-time :cycle-selection false)]
      (when (should-trigger? :cycle-selection was-pressed is-pressed)
        (kb-proto/emit-keyboard-input!
          :content/cycle-selection
          {:player-uuid player-uuid
           :client-session-id client-session-id
           :logical-side :client}))
      (swap! last-poll-time assoc :cycle-selection is-pressed))

    nil
    (catch Exception e
      (log/warn e "Error polling GLFW inputs"))))

(defn reset-polling-state!
  "Reset debounce state (for testing or platform restart)"
  []
  (reset! last-poll-time {})
  nil)
