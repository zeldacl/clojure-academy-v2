(ns cn.li.forge1201.commands
  "Brigadier command registration for Forge 1.20.1.

  This namespace builds Brigadier command trees from the command DSL metadata
  and registers them with Minecraft's command system."
  (:require [cn.li.mcmod.command.metadata :as cmd-meta]
            [cn.li.mcmod.command.context :as cmd-ctx]
            [cn.li.mcmod.command.actions :as cmd-actions]
            [cn.li.forge1201.command-executor]  ; Load action implementations
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.brigadier CommandDispatcher]
           [com.mojang.brigadier.builder ArgumentBuilder]
           [com.mojang.brigadier.builder LiteralArgumentBuilder RequiredArgumentBuilder]
           [com.mojang.brigadier.arguments StringArgumentType IntegerArgumentType FloatArgumentType BoolArgumentType]
           [com.mojang.brigadier.context CommandContext]
           [net.minecraft.commands CommandSourceStack]
           [net.minecraft.server.level ServerPlayer]))

(defn- entity-arg-player-type []
  (clojure.lang.Reflector/invokeStaticMethod
    "net.minecraft.commands.arguments.EntityArgument"
    "player"
    (object-array 0)))

(defn- entity-arg-get-player [^CommandContext brigadier-ctx arg-name]
  (clojure.lang.Reflector/invokeStaticMethod
    "net.minecraft.commands.arguments.EntityArgument"
    "getPlayer"
    (object-array [brigadier-ctx arg-name])))

;; ============================================================================
;; Argument Type Mapping
;; ============================================================================

(defn map-argument-type
  "Map DSL argument type to Brigadier ArgumentType.

  Args:
    arg-spec: ArgumentSpec record

  Returns:
    Brigadier ArgumentType instance"
  [arg-spec]
  (case (:type arg-spec)
    :player (entity-arg-player-type)
    :string (StringArgumentType/string)
    :word (StringArgumentType/word)
    :greedy-string (StringArgumentType/greedyString)
    :integer (IntegerArgumentType/integer)
    :float (FloatArgumentType/floatArg)
    :boolean (BoolArgumentType/bool)
    :enum (StringArgumentType/word)  ; Enums are strings with suggestions
    (StringArgumentType/string)))    ; Default to string

;; ============================================================================
;; Argument Extraction
;; ============================================================================

(defn extract-argument-value
  "Extract argument value from Brigadier CommandContext.

  Args:
    ^CommandContext brigadier-ctx: Brigadier command context
    arg-name: String - Argument name
    arg-type: Keyword - DSL argument type

  Returns:
    Extracted value"
  [^CommandContext brigadier-ctx arg-name arg-type]
  (try
    (case arg-type
      :player (entity-arg-get-player brigadier-ctx arg-name)
      :string (StringArgumentType/getString brigadier-ctx arg-name)
      :word (StringArgumentType/getString brigadier-ctx arg-name)
      :greedy-string (StringArgumentType/getString brigadier-ctx arg-name)
      :integer (IntegerArgumentType/getInteger brigadier-ctx arg-name)
      :float (FloatArgumentType/getFloat brigadier-ctx arg-name)
      :boolean (BoolArgumentType/getBool brigadier-ctx arg-name)
      :enum (StringArgumentType/getString brigadier-ctx arg-name)
      (StringArgumentType/getString brigadier-ctx arg-name))
    (catch Exception e
      (log/warn "Failed to extract argument" arg-name ":" (ex-message e))
      nil)))

(defn extract-all-arguments
  "Extract all arguments from Brigadier context.

  Args:
    ^CommandContext brigadier-ctx: Brigadier command context
    arg-specs: Vector of ArgumentSpec records

  Returns:
    Map of argument-name -> value"
  [^CommandContext brigadier-ctx arg-specs]
  (into {}
        (keep (fn [arg-spec]
                (let [arg-name (name (:name arg-spec))
                      arg-type (:type arg-spec)
                      value (extract-argument-value brigadier-ctx arg-name arg-type)]
                  (when value
                    [(keyword arg-name) value])))
              arg-specs)))

;; ============================================================================
;; Command Execution
;; ============================================================================

(defn execute-command
  "Execute a command handler and process the result.

  Args:
    executor-fn: Command handler function
    ^CommandContext brigadier-ctx: Brigadier command context
    arg-specs: Vector of ArgumentSpec records
    target-player: Optional target player (for admin commands)

  Returns:
    Integer - Brigadier success code (1 for success, 0 for failure)"
  [executor-fn ^CommandContext brigadier-ctx arg-specs target-player]
  (try
    (let [^CommandSourceStack source (.getSource brigadier-ctx)
          ^ServerPlayer player (try (.getPlayerOrException source)
                   (catch Exception _ nil))
          world (when player (.level player))
          arguments (extract-all-arguments brigadier-ctx arg-specs)
          ctx (cmd-ctx/create-context
                {:player player
                 :world world
                 :source source
                 :arguments arguments
                 :target-player target-player})
          action-result (executor-fn ctx)]

      ;; Execute the action
      (when action-result
        (cmd-actions/execute action-result ctx))

      ;; Return success
      1)
    (catch Exception e
      (log/error "Command execution failed:" (ex-message e))
      0)))

;; ============================================================================
;; Command Node Building
;; ============================================================================

(defn build-executor
  "Build a Brigadier command executor.

  Args:
    executor-fn: Command handler function
    arg-specs: Vector of ArgumentSpec records
    target-player-arg: Optional name of target player argument

  Returns:
    Brigadier Command instance"
  [executor-fn arg-specs target-player-arg]
  (reify com.mojang.brigadier.Command
    (^int run [_ ^CommandContext ctx]
      (let [target-player (when target-player-arg
                            (try
                              (entity-arg-get-player ctx target-player-arg)
                              (catch Exception _ nil)))]
        (int (execute-command executor-fn ctx arg-specs target-player))))))

(defn build-argument-node
  "Build a Brigadier argument node.

  Args:
    arg-spec: ArgumentSpec record
    executor-fn: Optional executor function (for last argument)
    all-arg-specs: All argument specs (for extraction)
    target-player-arg: Optional target player argument name

  Returns:
    RequiredArgumentBuilder"
  [arg-spec executor-fn all-arg-specs target-player-arg]
  (let [arg-name (name (:name arg-spec))
        arg-type (map-argument-type arg-spec)
        builder (RequiredArgumentBuilder/argument arg-name arg-type)]
    (when executor-fn
      (.executes builder (build-executor executor-fn all-arg-specs target-player-arg)))
    builder))

(defn build-arguments-chain
  "Build a chain of argument nodes.

  Args:
    arg-specs: Vector of ArgumentSpec records
    executor-fn: Executor function
    target-player-arg: Optional target player argument name

  Returns:
    First argument node with chained arguments"
  [arg-specs executor-fn target-player-arg]
  (when (seq arg-specs)
    (loop [remaining (reverse arg-specs)
           ^ArgumentBuilder current-node nil]
      (if (empty? remaining)
        current-node
        (let [arg-spec (first remaining)
              is-last? (nil? current-node)
              ^RequiredArgumentBuilder node (build-argument-node
                                            arg-spec
                                            (when is-last? executor-fn)
                                            arg-specs
                                            target-player-arg)]
          (when current-node
            (.then node current-node))
          (recur (rest remaining) node))))))

(defn build-subcommand-node
  "Build a Brigadier node for a subcommand.

  Args:
    subcommand-name: String - Subcommand name
    subcommand-spec: SubcommandSpec record
    parent-args: Vector of parent command arguments
    target-player-arg: Optional target player argument name

  Returns:
    LiteralArgumentBuilder"
  [subcommand-name subcommand-spec parent-args target-player-arg]
  (let [^LiteralArgumentBuilder literal (LiteralArgumentBuilder/literal subcommand-name)
        executor-fn (:executor-fn subcommand-spec)
        sub-args (:arguments subcommand-spec)
        all-args (vec (concat parent-args sub-args))]
    (if (seq sub-args)
      (let [^RequiredArgumentBuilder arg-chain (build-arguments-chain sub-args executor-fn target-player-arg)]
        (.then literal arg-chain))
      (when executor-fn
        (.executes literal (build-executor executor-fn all-args target-player-arg))))
    literal))

(defn build-command-node
  "Build a Brigadier command node from DSL spec.

  Args:
    command-spec: CommandSpec record

  Returns:
    LiteralArgumentBuilder"
  [command-spec]
  (let [command-id (:id command-spec)
        ^LiteralArgumentBuilder literal (LiteralArgumentBuilder/literal command-id)
        permission-level (:permission-level command-spec)]

    ;; Set permission requirement
    (.requires literal
      (reify java.util.function.Predicate
        (test [_ source]
          (.hasPermission ^CommandSourceStack source (int permission-level)))))

    ;; Handle command tree (with subcommands)
    (if-let [subcommands (:subcommands command-spec)]
      (let [parent-args (:arguments command-spec)
            target-player-arg (when (seq parent-args)
                                (let [first-arg (first parent-args)]
                                  (when (= :player (:type first-arg))
                                    (name (:name first-arg)))))]
        ;; Build parent arguments if any
        (if (seq parent-args)
          (let [arg-chain-builder (fn [^ArgumentBuilder parent-node]
                                    (doseq [[sub-name sub-spec] subcommands]
                                      (let [^LiteralArgumentBuilder sub-node (build-subcommand-node
                                                                            (name sub-name)
                                                                            sub-spec
                                                                            parent-args
                                                                            target-player-arg)]
                                        (.then parent-node sub-node)))
                                    parent-node)
                ^RequiredArgumentBuilder first-arg-node (build-argument-node
                                                         (first parent-args)
                                                         nil
                                                         parent-args
                                                         target-player-arg)]
            (arg-chain-builder first-arg-node)
            (.then literal first-arg-node))
          ;; No parent arguments, attach subcommands directly
          (doseq [[sub-name sub-spec] subcommands]
            (let [^LiteralArgumentBuilder sub-node (build-subcommand-node
                                                    (name sub-name)
                                                    sub-spec
                                                    []
                                                    nil)]
              (.then literal sub-node)))))

      ;; Simple command (no subcommands)
      (let [executor-fn (:executor-fn command-spec)
            arg-specs (:arguments command-spec)]
        (if (seq arg-specs)
          (let [^RequiredArgumentBuilder arg-chain (build-arguments-chain arg-specs executor-fn nil)]
            (.then literal arg-chain))
          (when executor-fn
            (.executes literal (build-executor executor-fn [] nil))))))

    literal))

;; ============================================================================
;; Command Registration
;; ============================================================================

(defn register-all-commands
  "Register all commands from metadata with Brigadier.

  Args:
    ^CommandDispatcher dispatcher: Brigadier command dispatcher
    _build-context: Command build context (unused for now)

  Returns:
    nil"
  [^CommandDispatcher dispatcher _build-context]
  (log/info "Registering commands with Brigadier...")

  ;; Ensure command definitions are loaded
  (try
    (require 'cn.li.ac.command.commands)
    (when-let [init-fn (requiring-resolve 'cn.li.ac.command.commands/init-commands!)]
      (init-fn))
    (catch Exception e
      (log/error "Failed to load command definitions:" (ex-message e))))

  ;; Register each command
  (doseq [command-id (cmd-meta/get-all-command-ids)]
    (try
      (log/info "Registering command:" command-id)
      (when-let [spec (cmd-meta/get-command-spec command-id)]
        (let [node (build-command-node spec)]
          (.register dispatcher node)
          (log/info "Successfully registered command:" command-id)))
      (catch Exception e
        (log/error "Failed to register command" command-id ":" (ex-message e)))))

  (log/info "Command registration complete")
  nil)
