(ns cn.li.mc1201.command.executor-core
  "Command action executor helpers for Minecraft-owned generic actions.

  Content-owned command actions are registered through the generic
  `cn.li.mcmod.command.actions` seam. This shared Minecraft-version namespace
  intentionally avoids content player-state schemas."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.advancements Advancement AdvancementProgress]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server.level ServerPlayer]))

(defn execute-send-message-action
  "Execute :send-message action.

  Args:
    action-map: Action data containing :message, :translate?, :args, :error?
    send-feedback-fn: Platform callback (message translate? args error?) -> nil

  Returns:
    {:success? true}"
  [action-map send-feedback-fn]
  (let [message (:message action-map)
        translate? (:translate? action-map true)
        args (:args action-map [])
        error? (:error? action-map false)]
    (send-feedback-fn message translate? args error?)
    {:success? true}))

(defn grant-advancement!
  "Grant a vanilla Minecraft advancement to a player.

  Args:
    player: ServerPlayer
    advancement-id: String resource location
    send-feedback-fn: (fn [message translate? args error?]) -> nil

  Returns:
    {:success? bool :message optional-string}"
  [^ServerPlayer player advancement-id send-feedback-fn]
  (try
    (let [server (.getServer player)
          advancement-manager (.getAdvancements server)
          resource-loc (ResourceLocation. advancement-id)
          ^Advancement advancement (.getAdvancement advancement-manager resource-loc)]
      (if-not advancement
        (do
          (send-feedback-fn "command.mcmod.grant_advancement.not_found" true [advancement-id] true)
          {:success? false :message "Advancement not found"})
        (let [player-advancements (.getAdvancements player)
              ^AdvancementProgress progress (.getOrStartProgress player-advancements advancement)]
          (doseq [criterion (.getRemainingCriteria progress)]
            (.award player-advancements advancement criterion))
          (send-feedback-fn "command.mcmod.grant_advancement.success" true
                            [advancement-id (.getName (.getGameProfile player))] false)
          {:success? true})))
    (catch Exception e
      (log/error "Failed to grant advancement:" (ex-message e))
      (send-feedback-fn (str "Error: " (ex-message e)) false [] true)
      {:success? false :message (ex-message e)})))

(defn execute-grant-advancement-action
  "Execute :grant-advancement action.

  Args:
    action-map: Action data containing :advancement-id, :player (platform obj)
    send-feedback-fn: Platform callback

  Returns:
    {:success? bool}"
  [action-map send-feedback-fn]
  (let [advancement-id (:advancement-id action-map)
        player (:player action-map)]
    (grant-advancement! player advancement-id send-feedback-fn)))
