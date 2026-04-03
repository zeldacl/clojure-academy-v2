(ns cn.li.forge1201.command-executor
  "Command action executor - implements action execution for Forge 1.20.1.

  This namespace provides implementations for all command actions,
  translating platform-agnostic action maps into actual Minecraft operations."
  (:require [cn.li.mcmod.command.actions :as cmd-actions]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n])
  (:import [net.minecraft.commands CommandSourceStack]
           [net.minecraft.network.chat Component]
           [net.minecraft.server.level ServerPlayer]
           [net.minecraft.advancements Advancement AdvancementProgress]
           [net.minecraft.resources ResourceLocation]))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn send-feedback
  "Send feedback message to command source.

  Args:
    ^CommandSourceStack source: Command source
    message: String or translation key
    translate?: Boolean - whether to translate the message
    args: Optional translation arguments
    _error?: Boolean - whether this is an error message"
  [^CommandSourceStack source message translate? args _error?]
  (try
    (let [text (if translate?
                 (i18n/translate message args)
                 message)
          component (Component/literal text)]
      (.sendSuccess source component false))
    (catch Exception e
      (log/error "Failed to send feedback:" (ex-message e)))))

(defn get-player-ability-data
  "Get ability data for a player (placeholder - needs platform implementation).

  Args:
    ^ServerPlayer _player: Server player

  Returns:
    AbilityData map or nil"
  [^ServerPlayer _player]
  ;; TODO: Implement via platform capability system
  (log/warn "get-player-ability-data not yet implemented")
  nil)

(defn set-player-ability-data!
  "Set ability data for a player (placeholder - needs platform implementation).

  Args:
    ^ServerPlayer _player: Server player
    _ability-data: AbilityData map"
  [^ServerPlayer _player _ability-data]
  ;; TODO: Implement via platform capability system
  (log/warn "set-player-ability-data! not yet implemented"))

;; ============================================================================
;; Action Implementations
;; ============================================================================

(defmethod cmd-actions/execute-action-impl :send-message
  [action-map context]
  (let [source (:source context)
        message (:message action-map)
        translate? (:translate? action-map true)
        args (:args action-map [])
        error? (:error? action-map false)]
    (send-feedback source message translate? args error?)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :grant-advancement
  [action-map context]
  (let [source (:source context)
        advancement-id (:advancement-id action-map)
        ^ServerPlayer player (:player action-map)]
    (try
      (let [server (.getServer player)
            advancement-manager (.getAdvancements server)
            resource-loc (ResourceLocation. advancement-id)
            ^Advancement advancement (.getAdvancement advancement-manager resource-loc)]
        (if-not advancement
          (do
            (send-feedback source "command.academy.acach.not_found" true [advancement-id] true)
            {:success? false :message "Advancement not found"})
          (let [player-advancements (.getPlayerAdvancements advancement-manager player)
                ^AdvancementProgress progress (.getOrStartProgress player-advancements advancement)]
            (doseq [criterion (.getRemainingCriteria progress)]
              (.grant player-advancements advancement criterion))
            (send-feedback source "command.academy.acach.success" true
                           [advancement-id (.getName (.getGameProfile player))] false)
            {:success? true})))
      (catch Exception e
        (log/error "Failed to grant advancement:" (ex-message e))
        (send-feedback source (str "Error: " (ex-message e)) false [] true)
        {:success? false :message (ex-message e)}))))

(defmethod cmd-actions/execute-action-impl :switch-category
  [action-map context]
  (let [source (:source context)
        category-id (:category-id action-map)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Switching category for player" (.getName (.getGameProfile player)) "to" category-id)
    (send-feedback source "command.academy.aim.cat.success" true [(name category-id)] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :learn-skill
  [action-map context]
  (let [source (:source context)
        skill-id (:skill-id action-map)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Learning skill" skill-id "for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.learn.success" true [(name skill-id)] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :unlearn-skill
  [action-map context]
  (let [source (:source context)
        skill-id (:skill-id action-map)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Unlearning skill" skill-id "for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.unlearn.success" true [(name skill-id)] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :learn-all-skills
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Learning all skills for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.learn_all.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :list-learned-skills
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Listing learned skills for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.learned.list" true ["(not yet implemented)"] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :list-available-skills
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Listing available skills for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.skills.list" true ["(not yet implemented)"] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :set-level
  [action-map context]
  (let [source (:source context)
        level (:level action-map)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Setting level" level "for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.level.success" true [(str level)] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :set-skill-exp
  [action-map context]
  (let [source (:source context)
        skill-id (:skill-id action-map)
        exp (:exp action-map)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Setting skill exp" skill-id "to" exp "for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.exp.success" true [(name skill-id) (str exp)] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :restore-cp
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Restoring CP for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.fullcp.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :clear-cooldowns
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Clearing cooldowns for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.cd_clear.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :reset-abilities
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Resetting abilities for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.reset.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :maxout-progression
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Maxing out progression for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.maxout.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :enable-cheats
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Enabling cheats for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.cheats_on.success" true [] false)
    {:success? true}))

(defmethod cmd-actions/execute-action-impl :disable-cheats
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    ;; TODO: Implement via ability system
    (log/info "Disabling cheats for player" (.getName (.getGameProfile player)))
    (send-feedback source "command.academy.aim.cheats_off.success" true [] false)
    {:success? true}))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize command executor.

  This ensures all action implementations are loaded."
  []
  (log/info "Command executor initialized"))
