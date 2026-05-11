(ns cn.li.fabric1201.command-executor
  "Command action executor - platform-specific Fabric implementation delegating to shared core.

  This namespace adapts platform-specific operations (Minecraft component sending,
  advancement granting) and delegates all business logic to mc-1.20.1/command/executor_core.
  Uses Fabric's ServerPlayer and Component APIs."
  (:require [cn.li.mc1201.command.executor-core :as executor-core]
            [cn.li.mcmod.command.actions :as cmd-actions]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mc1201.runtime.sync-core :as runtime-sync])
  (:import [net.minecraft.commands CommandSourceStack]
           [net.minecraft.network.chat Component]
           [net.minecraft.server.level ServerPlayer]))

;; ============================================================================
;; Platform Adapters (Fabric-Specific Operations)
;; ============================================================================

(defn- send-feedback-impl
  "Platform implementation: send feedback using MC Component API.
  
  Note: Fabric uses the same net.minecraft.network.chat.Component API as Forge."
  [^CommandSourceStack source message translate? args _error?]
  (try
    (let [text (if translate?
                 (i18n/translate message args)
                 message)
          component (Component/literal text)]
      (.sendSuccess source component false))
    (catch Exception e
      (log/error "Failed to send feedback:" (ex-message e)))))

;; ============================================================================
;; Action Implementations (Delegating to executor-core with platform callbacks)
;; ============================================================================

(defmethod cmd-actions/execute-action-impl :send-message
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))]
    (executor-core/execute-send-message-action action-map send-feedback-fn)))

(defmethod cmd-actions/execute-action-impl :grant-advancement
  [action-map context]
  (let [^CommandSourceStack source (:source context)
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))]
    (executor-core/execute-grant-advancement-action action-map send-feedback-fn)))

(defmethod cmd-actions/execute-action-impl :switch-category
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-switch-category-action action-map' send-feedback-fn mark-dirty-fn)))

(defmethod cmd-actions/execute-action-impl :learn-node
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-learn-node-action action-map' send-feedback-fn mark-dirty-fn)))

(defmethod cmd-actions/execute-action-impl :unlearn-node
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-unlearn-node-action action-map' send-feedback-fn mark-dirty-fn)))

(defmethod cmd-actions/execute-action-impl :learn-all-nodes
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-learn-all-nodes-action action-map' send-feedback-fn mark-dirty-fn)))

(defmethod cmd-actions/execute-action-impl :list-learned-nodes
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-list-learned-nodes-action action-map' send-feedback-fn (fn [_] nil))))

(defmethod cmd-actions/execute-action-impl :list-available-nodes
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-list-available-nodes-action action-map' send-feedback-fn (fn [_] nil))))

(defmethod cmd-actions/execute-action-impl :set-level
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-set-level-action action-map' send-feedback-fn mark-dirty-fn)))

(defmethod cmd-actions/execute-action-impl :set-node-exp
  [action-map context]
  (let [^ServerPlayer player (:player action-map)
        ^CommandSourceStack source (:source context)
        player-uuid (str (.getUUID player))
        send-feedback-fn (fn [msg trans? args err?]
                           (send-feedback-impl source msg trans? args err?))
        mark-dirty-fn (fn [uuid]
                        (runtime-sync/mark-player-dirty! uuid))
        action-map' (assoc action-map :player-uuid player-uuid)]
    (executor-core/execute-set-node-exp-action action-map' send-feedback-fn mark-dirty-fn)))

;; TODO: Continue with remaining action implementations
;; (defmethod cmd-actions/execute-action-impl :... [action-map context] ...)
