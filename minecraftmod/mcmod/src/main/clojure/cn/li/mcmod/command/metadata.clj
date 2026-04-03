(ns cn.li.mcmod.command.metadata
  "Command metadata system - single source of truth for command registration.

  This module provides metadata-driven command registration, ensuring platform
  code does not contain hardcoded command names. All registration information
  is dynamically retrieved from the command DSL system.

  Architecture:
  - Platform code queries this module for what to register
  - This module queries command DSL for available commands
  - Game content lives in ac; platform code stays generic"
  (:require [cn.li.mcmod.util.log :as log]))

;; Forward declaration - will be resolved at runtime when ac namespace loads
(declare get-command-registry)

(defn- resolve-command-registry
  "Lazily resolve the command registry from ac namespace"
  []
  (when-let [registry-var (requiring-resolve 'cn.li.ac.command.dsl/command-registry)]
    @registry-var))

;; ============================================================================
;; Command Registration Metadata
;; ============================================================================

(defn get-all-command-ids
  "Returns a sequence of all registered command IDs from the command DSL.

  Platform code should iterate over this list to register all commands,
  without knowing specific command names.

  Returns:
    Sequence of command ID strings (e.g., [\"acach\" \"aim\" \"aimp\"])"
  []
  (when-let [registry (resolve-command-registry)]
    (keys registry)))

(defn get-command-spec
  "Retrieves the full command specification from the DSL.

  Args:
    command-id: String - Command identifier

  Returns:
    CommandSpec record with all properties"
  [command-id]
  (when-let [registry (resolve-command-registry)]
    (get registry command-id)))

(defn get-command-permission-level
  "Returns the permission level for a command.

  Args:
    command-id: String - Command identifier

  Returns:
    Integer - Permission level (0=all, 2=op, 4=console)"
  [command-id]
  (when-let [spec (get-command-spec command-id)]
    (:permission-level spec)))

(defn get-command-arguments
  "Returns the arguments for a command.

  Args:
    command-id: String - Command identifier

  Returns:
    Vector of ArgumentSpec records"
  [command-id]
  (when-let [spec (get-command-spec command-id)]
    (:arguments spec)))

(defn get-command-executor
  "Returns the executor function for a command.

  Args:
    command-id: String - Command identifier

  Returns:
    Function or nil"
  [command-id]
  (when-let [spec (get-command-spec command-id)]
    (:executor-fn spec)))

(defn get-command-description
  "Returns the description for a command.

  Args:
    command-id: String - Command identifier

  Returns:
    String - Human-readable description"
  [command-id]
  (when-let [spec (get-command-spec command-id)]
    (:description spec)))

(defn get-subcommands
  "Returns the subcommands map for a command tree.

  Args:
    command-id: String - Command identifier

  Returns:
    Map of subcommand-name -> SubcommandSpec, or nil"
  [command-id]
  (when-let [spec (get-command-spec command-id)]
    (:subcommands spec)))

(defn has-subcommands?
  "Check if a command has subcommands (is a command tree).

  Args:
    command-id: String - Command identifier

  Returns:
    Boolean"
  [command-id]
  (boolean (seq (get-subcommands command-id))))

(defn get-subcommand-spec
  "Get a specific subcommand spec from a command tree.

  Args:
    command-id: String - Command identifier
    subcommand-name: String or keyword - Subcommand name

  Returns:
    SubcommandSpec record or nil"
  [command-id subcommand-name]
  (when-let [subcommands (get-subcommands command-id)]
    (get subcommands (keyword subcommand-name))))

(defn get-subcommand-executor
  "Get the executor function for a subcommand.

  Args:
    command-id: String - Command identifier
    subcommand-name: String or keyword - Subcommand name

  Returns:
    Function or nil"
  [command-id subcommand-name]
  (when-let [subcommand-spec (get-subcommand-spec command-id subcommand-name)]
    (:executor-fn subcommand-spec)))

(defn get-subcommand-arguments
  "Get the arguments for a subcommand.

  Args:
    command-id: String - Command identifier
    subcommand-name: String or keyword - Subcommand name

  Returns:
    Vector of ArgumentSpec records"
  [command-id subcommand-name]
  (when-let [subcommand-spec (get-subcommand-spec command-id subcommand-name)]
    (:arguments subcommand-spec)))

(defn get-subcommand-permission-level
  "Get the permission level for a subcommand (inherits from parent if not set).

  Args:
    command-id: String - Command identifier
    subcommand-name: String or keyword - Subcommand name

  Returns:
    Integer - Permission level"
  [command-id subcommand-name]
  (when-let [subcommand-spec (get-subcommand-spec command-id subcommand-name)]
    (or (:permission-level subcommand-spec)
        (get-command-permission-level command-id))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-command-metadata!
  "Initialize command metadata system.

  Called during mod initialization to ensure command DSL is ready.
  Platform code should call this before attempting registration."
  []
  ;; Command DSL is initialized when ac.command.commands namespace loads
  ;; This function exists for future initialization needs
  (log/info "Command metadata system initialized")
  nil)
