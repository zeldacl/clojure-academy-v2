(ns cn.li.ac.input-ids
  "Keybinding configuration registry - AC is the single source of truth.
   Contains: input IDs, shortcut keys, handlers, and metadata.

   Schema for each input ID:
   {:input-id      keyword          ; :content/slot-0, :content/toggle-primary-state
    :scheme        keyword          ; :alternative (configurable) / :original (platform-fixed)
    :description   string           ; for UI/documentation
    :event-type    keyword          ; :short-press / :press / :release

    ;; Only when :scheme :alternative
    :key-mapping   {:key int                  ; GLFW_KEY_Z etc.
                    :translation-key string  ; i18n key
                    :category string}        ; keybind category

    ;; Only when :scheme :original
    :fixed-key     keyword          ; :lmb / :rmb / :r / :f (documentation only)

    :handler       fn or symbol     ; handler function when key is pressed
                                    ; signature: (fn [context] ...)
                                    ; context := {:player-uuid uuid-string
                                    ;             :client-session-id session-id
                                    ;             :logical-side :client}
   }"
  (:require [cn.li.ac.terminal.client.actions :as terminal-actions]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]
            [cn.li.ac.ability.client.screens.spell-composer-reactive :as spell-composer]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

;; ===== Handler Function Implementations (defined before registry) =====
;; These will be called when corresponding keys are pressed.
;; Context structure: {:player-uuid string, :client-session-id string, :logical-side :client}

(defn- content-key-allowed?
  "Upstream isPlayerInGame guard (lambda KeyManager.tick): content keys fire
   only while in game — no GUI open — except the terminal's own screen, where
   Alt must still close it (upstream TerminalUI is a non-foreground auxgui so
   keys keep working there)."
  []
  (or (not (client-bridge/screen-active?))
      (terminal-actions/terminal-screen-open?)))

(defn- on-cycle-selection
  "Handle cycle selection (C key press, upstream KEY_SWITCH_PRESET) — switch
   to next preset."
  [{:keys [player-uuid]}]
  (log/debug "Cycle selection — switching preset" {:uuid player-uuid})
  (when (and (content-key-allowed?) player-uuid)
    (keybinds/switch-preset! player-uuid)))

(defn- on-toggle-primary-state
  "Handle primary state toggle (V key input) — toggle ability mode on/off.

   This handler is intentionally thin. Platform input layers decide whether the
   gesture is a short press or a long hold before they emit this input; AC only
   consumes the already-filtered event and executes the business action."
  [{:keys [player-uuid]}]
  (log/debug "Toggle primary state — toggling ability mode" {:uuid player-uuid})
  (when (and (content-key-allowed?) player-uuid)
    (keybinds/trigger-mode-switch! player-uuid)))

(defn- on-toggle-debug-overlay
  "Handle debug overlay toggle (F4 key press). Matching original AcademyCraft
   DebugConsole (ACKeyManager.addKeyHandler(\"debug_console\", KEY_F4, ...)) —
   cycles the debug info overlay through none -> normal -> show-exp -> none."
  [_context]
  (when (content-key-allowed?)
    (runtime-hooks/toggle-debug-overlay-state!)))

(defn- on-open-node-editor
  "Handle the node editor dev-tool key (G, upstream: none). This key and
   editor_dev_tool are supported client entry points. Opens the shared
   sample skill (thunder-bolt) in skill mode: there is no file-picker UI
   yet, so this is a fixed, documented default shared with the item.
   The missing file-picker is a separate follow-up, not an entry-point gap."
  [{:keys [player-uuid]}]
  (when (and (content-key-allowed?) player-uuid)
    (if-let [path (node-editor/default-sample-skill-resource-path "ac/skills-v3/thunder-bolt.edn")]
      (node-editor/open! player-uuid path :skill)
      (log/warn "Node editor: ac/skills-v3/thunder-bolt.edn is not on-disk (packaged jar?) -- no writable path to open"))))

(defn- on-open-spell-composer
  "Handle the spell composer dev-tool key (K, upstream: none). Unlike
   the node editor, spell-composer/open! takes only a player-uuid -- no
   file path to resolve, so there is no equivalent failure case here."
  [{:keys [player-uuid]}]
  (when (and (content-key-allowed?) player-uuid)
    (spell-composer/open! player-uuid)))

(defn- on-toggle-terminal
  "Handle terminal toggle (Left Alt / GLFW_KEY_LEFT_ALT).
   Matching original AcademyCraft TerminalUI.keyHandler (KEY_LMENU)."
  [_context]
  (log/debug "[AC-Terminal] toggle key pressed")
  (if (content-key-allowed?)
    (if-let [player (client-bridge/get-client-player)]
      (do
        (log/debug "[AC-Terminal] got player, toggling terminal")
        (terminal-actions/toggle-terminal! player))
      (log/warn "[AC-Terminal] get-client-player returned nil — bridge not installed?"))
    (log/debug "[AC-Terminal] toggle suppressed — GUI open (upstream isPlayerInGame)")))

;; ==== Input ID Configuration Registry ====

(def ^:private registry
  "Keybinding registry. Registered to mcmod.protocol.keyboard-input at bootstrap."
  {
    ;; ===== :alternative scheme (fully configurable) =====
    ;; These shortcuts are configured by AC. Platforms create KeyMappings from :key-mapping.
    ;;
    ;; Ability slot keys (upstream default: mouse-left/mouse-right/R/F) and
    ;; the preset-editor key are NOT registered here: the platform key-mapping
    ;; adapters create vanilla KeyMappings for the :ability-key-* /
    ;; :edit-preset-key config keys (seeded from the config values), so they
    ;; appear in Options > Controls and the Settings app shares the same
    ;; KeyMapping instances. Input dispatch stays polled per-frame by
    ;; keybinds/tick-keys! (slot-glfw-keys in the platform key-state-fn),
    ;; reading the live KeyMapping binding.

    :content/cycle-selection
    {:input-id :content/cycle-selection
     :scheme :alternative
     :description "Switch preset"
     :event-type :press
     :key-mapping {:key 67  ; GLFW_KEY_C — upstream KEY_SWITCH_PRESET
                   :translation-key "key.content.cycle.selection"
                   :category "keybind.category.content"}
     :handler #'on-cycle-selection}

    ;; V key — toggle ability mode on/off (or abort active skills when in ability mode)
    :content/toggle-primary-state
    {:input-id :content/toggle-primary-state
     :scheme :alternative
     :description "Toggle ability mode"
     :event-type :short-press
     :key-mapping {:key 86  ; GLFW_KEY_V
                   :translation-key "key.content.toggle.primary"
                   :category "keybind.category.content"}
     :handler #'on-toggle-primary-state}

    ;; F4 — cycle debug info overlay (none/normal/show-exp), upstream DebugConsole
    :content/toggle-debug-overlay
    {:input-id :content/toggle-debug-overlay
     :scheme :alternative
     :description "Toggle debug info overlay"
     :event-type :press
     :key-mapping {:key 293  ; GLFW_KEY_F4 — upstream DebugConsole KEY_F4
                   :translation-key "key.content.toggle.debug.overlay"
                   :category "keybind.category.content"}
     :handler #'on-toggle-debug-overlay}

    ;; Terminal toggle — matching original AcademyCraft KEY_LMENU (Left Alt)
    :content/toggle-terminal
    {:input-id :content/toggle-terminal
     :scheme :alternative
     :description "Toggle MisakaCloud Terminal"
     :event-type :short-press
     :key-mapping {:key 342  ; GLFW_KEY_LEFT_ALT = 342
                   :translation-key "key.content.toggle.terminal"
                   :category "keybind.category.content"}
     :handler #'on-toggle-terminal}

    ;; G — open the node editor dev tool. This is the shared fixed-sample
    ;; entry point; the creative editor_dev_tool item targets the same screen.
    :content/open-node-editor
    {:input-id :content/open-node-editor
     :scheme :alternative
     :description "Open node editor (dev tool)"
     :event-type :press
     :key-mapping {:key 71  ; GLFW_KEY_G
                   :translation-key "key.content.open.node.editor"
                   :category "keybind.category.content"}
     :handler #'on-open-node-editor}

    ;; K — open the player spell composer dev tool. The creative
    ;; spell_composer_dev_tool item targets the same screen.
    :content/open-spell-composer
    {:input-id :content/open-spell-composer
     :scheme :alternative
     :description "Open spell composer (dev tool)"
     :event-type :press
     :key-mapping {:key 75  ; GLFW_KEY_K
                   :translation-key "key.content.open.spell.composer"
                   :category "keybind.category.content"}
     :handler #'on-open-spell-composer}
  })

;; ==== Public API ====

(defn get-input-ids
  "Get all registered input IDs configuration"
  []
  registry)

(defn get-input-id-config
  "Get configuration for a specific input ID"
  [input-id]
  (get registry input-id))

(defn bootstrap!
  "Initialize keybinding system. Called by platform layer after SPI installation.
   Registers all input IDs and handlers to mcmod.protocol.keyboard-input"
  []
  (try
    ;; Require the mcmod protocol namespace
    (require '[cn.li.mcmod.protocol.keyboard-input :as kb-proto])
    
    ;; Register each input ID and its handler
    (doseq [[_key config] registry]
      (let [input-id (:input-id config)
            handler (:handler config)]
        ((resolve (symbol "cn.li.mcmod.protocol.keyboard-input" "register-input-id!")) input-id handler)))
    
    (log/debug "AC keybindings bootstrapped successfully")
    nil
    
    (catch Exception e
      (log/stacktrace "Failed to bootstrap AC keybindings" e)
      (throw e))))
