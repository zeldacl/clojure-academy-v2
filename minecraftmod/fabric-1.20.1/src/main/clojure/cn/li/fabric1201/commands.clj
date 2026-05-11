(ns cn.li.fabric1201.commands
  "Brigadier command registration for Fabric 1.20.1.

  Delegates tree-building to the shared cn.li.mc1201.command.brigadier-tree
  namespace; this file only contains the Fabric-specific command registration
  entry point wired via ServerLifecycleEvents."
  (:require [cn.li.mcmod.command.metadata :as cmd-meta]
            [cn.li.mcmod.platform.command-runtime :as command-runtime]
            [cn.li.mc1201.command.brigadier-tree :as brig-tree]
            [cn.li.mc1201.command.action-impls]  ; Load shared action implementations
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.brigadier CommandDispatcher]))

;; ============================================================================
;; Command Registration
;; ============================================================================

(defn register-commands
  "Register all commands with the Brigadier dispatcher.

  Args:
    ^CommandDispatcher dispatcher: Brigadier command dispatcher

  Returns:
    nil"
  [^CommandDispatcher dispatcher]
  ;; Ensure command definitions are loaded
  (try
    (command-runtime/init-commands!)
    (catch Exception e
      (log/error "Failed to load command definitions:" (ex-message e))))

  ;; Register each command
  (doseq [command-id (cmd-meta/get-all-command-ids)]
    (try
      (log/info "Registering command:" command-id)
      (when-let [spec (cmd-meta/get-command-spec command-id)]
        (let [node (brig-tree/build-command-node spec)]
          (.register dispatcher node)
          (log/info "Successfully registered command:" command-id)))
      (catch Exception e
        (log/error "Failed to register command" command-id ":" (ex-message e)))))

  (log/info "Command registration complete"))
