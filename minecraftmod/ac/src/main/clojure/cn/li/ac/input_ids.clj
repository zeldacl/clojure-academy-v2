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
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

;; ===== Handler Function Implementations (defined before registry) =====
;; These will be called when corresponding keys are pressed.
;; Context structure: {:player-uuid string, :client-session-id string, :logical-side :client}

(defn- on-slot-0-activate
  "Handle slot 0 activation (slot key Z, index 0)"
  [context]
  (log/debug "Slot 0 activated" {:context context})
  ;; Slot keys are polled per-frame by keybinds/tick-keys!, not via event dispatch.
  ;; This handler exists for future event-based slot activation.
  nil)

(defn- on-slot-1-activate
  "Handle slot 1 activation (slot key X, index 1)"
  [context]
  (log/debug "Slot 1 activated" {:context context})
  nil)

(defn- on-cycle-selection
  "Handle cycle selection (R key press) — switch to next preset."
  [{:keys [player-uuid]}]
  (log/debug "Cycle selection — switching preset" {:uuid player-uuid})
  (when player-uuid
    (keybinds/switch-preset! player-uuid)))

(defn- on-toggle-primary-state
  "Handle primary state toggle (V key press) — toggle ability mode on/off."
  [{:keys [player-uuid]}]
  (log/debug "Toggle primary state — toggling ability mode" {:uuid player-uuid})
  (when player-uuid
    (keybinds/trigger-mode-switch! player-uuid)))

(defn- on-toggle-terminal
  "Handle terminal toggle (Left Alt / GLFW_KEY_LEFT_ALT).
   Matching original AcademyCraft TerminalUI.keyHandler (KEY_LMENU)."
  [_context]
  (log/info "[AC-Terminal] toggle key pressed")
  (if-let [player (client-bridge/get-client-player)]
    (do
      (log/info "[AC-Terminal] got player, toggling terminal")
      (terminal-actions/toggle-terminal! player))
    (log/warn "[AC-Terminal] get-client-player returned nil — bridge not installed?")))

;; ==== Input ID Configuration Registry ====

(def ^:private registry
  "Keybinding registry. Registered to mcmod.protocol.keyboard-input at bootstrap."
  {
    ;; ===== :alternative scheme (fully configurable) =====
    ;; These shortcuts are configured by AC. Platforms create KeyMappings from :key-mapping.
    
    :content/slot-0
    {:input-id :content/slot-0
     :scheme :alternative
     :description "Activate slot 0"
     :event-type :short-press
     :key-mapping {:key 90  ; GLFW_KEY_Z
                   :translation-key "key.content.slot.0"
                   :category "keybind.category.content"}
     :handler #'on-slot-0-activate}
    
    :content/slot-1
    {:input-id :content/slot-1
     :scheme :alternative
     :description "Activate slot 1"
     :event-type :short-press
     :key-mapping {:key 88  ; GLFW_KEY_X
                   :translation-key "key.content.slot.1"
                   :category "keybind.category.content"}
     :handler #'on-slot-1-activate}
    
    :content/cycle-selection
    {:input-id :content/cycle-selection
     :scheme :alternative
     :description "Cycle ability selection"
     :event-type :press
     :key-mapping {:key 82  ; GLFW_KEY_R
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
    
    (log/info "AC keybindings bootstrapped successfully")
    nil
    
    (catch Exception e
      (log/error e "Failed to bootstrap AC keybindings")
      (throw e))))
