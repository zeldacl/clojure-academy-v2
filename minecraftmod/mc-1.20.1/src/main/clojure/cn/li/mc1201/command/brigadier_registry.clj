(ns cn.li.mc1201.command.brigadier-registry
  "Shared Brigadier command registration flow.

  Platform modules should delegate registration to this namespace and
  keep only loader-specific entry signatures."
  (:require [cn.li.mcmod.command.metadata :as cmd-meta]
            [cn.li.mcmod.platform.command-runtime :as command-runtime]
            [cn.li.mc1201.command.brigadier-tree :as brig-tree]
            [cn.li.mc1201.command.action-impls] ; Ensure action implementations are loaded
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.brigadier CommandDispatcher]))

(defn- register-command!
  [^CommandDispatcher dispatcher command-id]
  (try
    (log/info "Registering command:" command-id)
    (when-let [spec (cmd-meta/get-command-spec command-id)]
      (let [node (brig-tree/build-command-node spec)]
        (.register dispatcher node)
        (log/info "Successfully registered command:" command-id)))
    (catch Exception e
      (log/error "Failed to register command" command-id ":" (ex-message e)))))

(defn register-all-commands!
  "Register all commands from metadata with Brigadier.

  Args:
    dispatcher: Brigadier command dispatcher
    opts: Optional map
      :platform - keyword for logging context (:forge/:fabric/etc.)

  Returns:
    nil"
  ([^CommandDispatcher dispatcher]
   (register-all-commands! dispatcher nil))
  ([^CommandDispatcher dispatcher {:keys [platform]}]
   (let [platform-label (or platform :generic)]
     (log/info "Registering commands with Brigadier" {:platform platform-label})

     (try
       (command-runtime/init-commands!)
       (catch Exception e
         (log/error "Failed to load command definitions:" (ex-message e))))

     (doseq [command-id (cmd-meta/get-all-command-ids)]
       (register-command! dispatcher command-id))

     (log/info "Command registration complete" {:platform platform-label})
     nil)))
