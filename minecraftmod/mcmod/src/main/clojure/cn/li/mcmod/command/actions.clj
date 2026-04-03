(ns cn.li.mcmod.command.actions
  "Command action execution protocol.

  Actions are platform-agnostic descriptions of what should happen when
  a command executes. The platform layer translates these actions into
  actual Minecraft operations.

  Action types:
    :send-message - Send a message to the command source
    :grant-advancement - Grant an advancement to a player
    :switch-category - Switch player's ability category
    :learn-skill - Learn a skill
    :unlearn-skill - Unlearn a skill
    :learn-all-skills - Learn all skills in current category
    :list-learned-skills - List learned skills
    :list-available-skills - List available skills
    :set-level - Set player ability level
    :set-skill-exp - Set skill experience
    :restore-cp - Restore CP to full
    :clear-cooldowns - Clear all cooldowns
    :reset-abilities - Reset all abilities
    :maxout-progression - Max out level progression
    :enable-cheats - Enable cheat mode
    :disable-cheats - Disable cheat mode"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Action Protocol
;; ============================================================================

(defprotocol IActionExecutor
  "Protocol for executing command actions"
  (execute-action [this action-map context]
    "Execute an action and return result.

    Args:
      action-map: Map describing the action to execute
      context: CommandContext

    Returns:
      Result map with :success? boolean and optional :message"))

;; ============================================================================
;; Action Validation
;; ============================================================================

(def valid-action-types
  #{:send-message
    :grant-advancement
    :switch-category
    :learn-skill
    :unlearn-skill
    :learn-all-skills
    :list-learned-skills
    :list-available-skills
    :set-level
    :set-skill-exp
    :restore-cp
    :clear-cooldowns
    :reset-abilities
    :maxout-progression
    :enable-cheats
    :disable-cheats})

(defn validate-action
  "Validate an action map.

  Args:
    action-map: Map with :action key

  Returns:
    Boolean - true if valid

  Throws:
    ex-info if invalid"
  [action-map]
  (when-not (map? action-map)
    (throw (ex-info "Action must be a map" {:action action-map})))

  (let [action-type (:action action-map)]
    (when-not action-type
      (throw (ex-info "Action map must have :action key" {:action action-map})))

    (when-not (contains? valid-action-types action-type)
      (throw (ex-info "Invalid action type"
                      {:action-type action-type
                       :valid-types valid-action-types}))))
  true)

;; ============================================================================
;; Action Execution (Multimethod)
;; ============================================================================

(defmulti execute-action-impl
  "Execute an action based on its type.

  This is a multimethod that dispatches on the :action key.
  Platform implementations should provide methods for each action type.

  Args:
    action-map: Map with :action key and action-specific data
    context: CommandContext

  Returns:
    Result map with :success? boolean and optional :message"
  (fn [action-map _context] (:action action-map)))

;; Default implementation logs a warning
(defmethod execute-action-impl :default
  [action-map _context]
  (log/warn "No implementation for action type:" (:action action-map))
  {:success? false
   :message (str "Unimplemented action: " (:action action-map))})

;; ============================================================================
;; Action Execution Wrapper
;; ============================================================================

(defn execute
  "Execute an action with validation.

  Args:
    action-map: Map describing the action
    context: CommandContext

  Returns:
    Result map with :success? boolean and optional :message"
  [action-map context]
  (try
    (validate-action action-map)
    (execute-action-impl action-map context)
    (catch Exception e
      (log/error "Error executing action:" (ex-message e))
      {:success? false
       :message (str "Error: " (ex-message e))})))

;; ============================================================================
;; Batch Action Execution
;; ============================================================================

(defn execute-batch
  "Execute multiple actions in sequence.

  Args:
    action-maps: Sequence of action maps
    context: CommandContext

  Returns:
    Vector of result maps"
  [action-maps context]
  (mapv #(execute % context) action-maps))
