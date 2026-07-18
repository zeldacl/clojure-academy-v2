(ns cn.li.mc1201.command.action-impls
  "Shared command action defmethod implementations for Minecraft-owned actions.

  Content-specific action ids and state mutations are registered by content
  modules through cn.li.mcmod.command.actions."
  (:require [cn.li.mc1201.command.executor-core :as executor]
            [cn.li.mc1201.command.feedback :as feedback]
            [cn.li.mcmod.command.actions :as cmd-actions])
  (:import [net.minecraft.commands CommandSourceStack]))

(defmethod cmd-actions/execute-action-impl :send-message
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (feedback/send-feedback! source msg trans? args err?))]
    (executor/execute-send-message-action action-map send-feedback-fn)))

(defmethod cmd-actions/execute-action-impl :grant-advancement
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (feedback/send-feedback! source msg trans? args err?))]
    (executor/execute-grant-advancement-action action-map send-feedback-fn)))
