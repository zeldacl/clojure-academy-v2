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
(def GLFW_KEY_C 67)
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
   Upstream AcademyCraft ClientHandler.keyActivate: toggle fires on KEY
   RELEASE only if held < 300ms (ClientHandler.java onKeyUp/onKeyDown).
   V is a standalone toggle key upstream — it is not reused for any ability
   slot (those default to mouse-left/mouse-right/R/F)."
  (atom 0))

(defn poll-all-inputs!
  "Poll hardcoded key inputs and emit keyboard events.

   Called once per tick by Forge/Fabric platform layer.

   For Forge: supplements the KeyMapping event system (handles edge cases).
   For Fabric: the PRIMARY input mechanism (Fabric has no KeyMapping events).

   Polling targets:
   - :content/cycle-selection (C key — upstream KEY_SWITCH_PRESET)
   - :content/toggle-primary-state (V key — upstream KEY_ACTIVATE_ABILITY)

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
     ;; Cycle selection / switch preset (C key — upstream KEY_SWITCH_PRESET)
     (let [key-code GLFW_KEY_C
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

     ;; Toggle primary state (V key — mode switch, upstream KEY_ACTIVATE_ABILITY)
     ;; Upstream AcademyCraft ClientHandler: toggle fires on KEY RELEASE
     ;; only if held < 300ms.
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

;; ============================================================================
;; Per-frame held-key state — shared by Forge (runtime_bridge.clj) and Fabric
;; (keyboard_init.clj) key-state-fn callbacks for keybinds/tick-keys!. Was
;; previously duplicated verbatim in both loader packages; extracted here so
;; a key remap (e.g. the ability-slot/preset-editor/skill-tree key alignment
;; pass) only needs to change one place.
;; ============================================================================

;; Upstream AcademyCraft ClientHandler.keyIDsInit default: MOUSE_LEFT,
;; MOUSE_RIGHT, R, F → ability slots 0-3.
(def slot-glfw-keys [:mouse-left :mouse-right 82 70])
(def movement-glfw-keys {:forward 87   ;; W
                         :back 83      ;; S
                         :left 65      ;; A
                         :right 68})   ;; D
;; :primary = N (78), :secondary = M (77) — raw key identity only; which
;; screen each opens is decided in keybinds.clj/tick-keys!, matching upstream
;; AcademyCraft's KEY_EDIT_PRESET = N (ClientHandler.java). Upstream has no
;; binding on M at all; the rewrite-only skill-tree viewer (no upstream
;; equivalent — upstream reaches it only via the terminal app) uses M so it
;; doesn't collide with N's upstream-aligned meaning.
(def screen-glfw-keys {:primary 78 :secondary 77})

(defn glfw-key-state-fn
  "key-state-fn callback for keybinds/tick-keys!. Takes [:slot idx],
   [:movement kw], or [:screen kw] and returns boolean key/mouse-button state
   from GLFW (via the installed KeySchemeProvider SPI)."
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

(def no-key-down-fn
  "key-state-fn used while a Screen (chat/inventory/GUI) is open: report every
   key as released so typed characters never fire gameplay keybinds (e.g. 'm'
   while typing '/aim' in chat opening the preset editor) and held keys get a
   clean release transition instead of sticking."
  (constantly false))
