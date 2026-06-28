(ns cn.li.mc1201.command.action-impls
  "Shared command action defmethod implementations for Minecraft-owned actions.

  Content-specific action ids and state mutations are registered by content
  modules through cn.li.mcmod.command.actions."
  (:require [cn.li.mc1201.command.executor-core :as executor]
            [cn.li.mcmod.command.actions :as cmd-actions]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n])
  (:import [net.minecraft.commands CommandSourceStack]
           [net.minecraft.network.chat Component]))

(defn- send-feedback-impl
  "Shared MC feedback implementation via Component API."
  [^CommandSourceStack source message translate? args _error?]
  (try
    (let [text (if translate?
                 (i18n/translate message)
                 message)
          component (Component/literal text)]
      (.sendSuccess source component false))
    (catch Exception e
      (log/error "Failed to send feedback:" (ex-message e)))))

(defmethod cmd-actions/execute-action-impl :send-message
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))]
    (executor/execute-send-message-action action-map send-feedback-fn)))

(defmethod cmd-actions/execute-action-impl :grant-advancement
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))]
    (executor/execute-grant-advancement-action action-map send-feedback-fn)))
