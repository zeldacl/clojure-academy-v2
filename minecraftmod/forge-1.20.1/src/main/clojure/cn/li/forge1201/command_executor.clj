(ns cn.li.forge1201.command-executor
  "Command action executor - implements action execution for Forge 1.20.1.

  This namespace provides implementations for all command actions,
  translating platform-agnostic action maps into actual Minecraft operations."
  (:require [cn.li.mcmod.command.actions :as cmd-actions]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.forge1201.runtime.sync :as runtime-sync]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [clojure.string :as str])
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

(def ^:private default-ability-data
  {:category-id nil
   :learned-skills #{}
   :skill-exps {}
   :level 1
   :level-progress 0.0})

(defn- player-uuid
  [^ServerPlayer player]
  (str (.getUUID player)))

(defn- normalize-runtime-state
  [state]
  (-> (or state {})
      (update :ability-data #(merge default-ability-data (or % {})))
      (update :resource-data #(or % {}))
      (update :cooldown-data #(or % {}))
      (update :preset-data #(or % {}))
      (update :develop-data #(or % {}))
      (update :terminal-data #(or % {:terminal-installed? false
                                     :installed-apps #{}}))))

(defn- update-player-runtime-data!
  [^ServerPlayer player f]
  (let [uuid (player-uuid player)
        current (normalize-runtime-state (power-runtime/get-or-create-player-state! uuid))
        updated (normalize-runtime-state (f current))]
    (power-runtime/set-player-state! uuid updated)
    (runtime-sync/mark-player-dirty! uuid)
    updated))

(defn- with-command-action
  [^CommandSourceStack source f]
  (try
    (f)
    (catch Exception e
      (log/error "Command action execution failed:" (ex-message e))
      (send-feedback source (str "Error: " (ex-message e)) false [] true)
      {:success? false :message (ex-message e)})))

(defn get-player-runtime-data
  "Get runtime data for a player.

  Args:
    ^ServerPlayer _player: Server player

  Returns:
    RuntimeData map or nil"
  [^ServerPlayer player]
  (normalize-runtime-state
    (power-runtime/get-or-create-player-state! (player-uuid player))))

(defn set-player-runtime-data!
  "Set runtime data for a player.

  Args:
    ^ServerPlayer _player: Server player
    runtime-data: RuntimeData map"
  [^ServerPlayer player runtime-data]
  (let [uuid (player-uuid player)]
    (power-runtime/set-player-state! uuid (normalize-runtime-state runtime-data))
    (runtime-sync/mark-player-dirty! uuid)
    true))

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
          (let [player-advancements (.getAdvancements player)
                ^AdvancementProgress progress (.getOrStartProgress player-advancements advancement)]
            (doseq [criterion (.getRemainingCriteria progress)]
              (.award player-advancements advancement criterion))
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
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (assoc-in state [:ability-data]
                      (assoc default-ability-data :category-id category-id))))
        (log/info "Switching category for player" (.getName (.getGameProfile player)) "to" category-id)
        (send-feedback source "command.academy.aim.cat.success" true [(name category-id)] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :learn-node
  [action-map context]
  (let [source (:source context)
    node-id (:node-id action-map)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (-> state
                (update-in [:ability-data :learned-skills] (fnil conj #{}) node-id)
                (update-in [:ability-data :skill-exps]
                           (fn [exps]
                             (if (contains? (or exps {}) node-id)
                               exps
                               (assoc (or exps {}) node-id 0.0)))))))
        (log/info "Learning node" node-id "for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.node.learn.success" true [(name node-id)] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :unlearn-node
  [action-map context]
  (let [source (:source context)
    node-id (:node-id action-map)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (-> state
                (update-in [:ability-data :learned-skills] disj node-id)
                (update-in [:ability-data :skill-exps] dissoc node-id))))
        (log/info "Unlearning node" node-id "for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.node.unlearn.success" true [(name node-id)] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :learn-all-nodes
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (let [state (get-player-runtime-data player)
              category-id (get-in state [:ability-data :category-id])
              all-skill-ids (when category-id
                              (->> (power-runtime/get-skills-for-category category-id)
                                   (map :id)
                                   (filter identity)
                                   set))
              _ (when (empty? all-skill-ids)
                  (log/warn "learn-all-nodes: no skills found for category" category-id))]
          (update-player-runtime-data!
            player
            (fn [s]
              (if (seq all-skill-ids)
                (-> s
                    (update-in [:ability-data :learned-skills]
                                (fnil into #{}) all-skill-ids)
                    (update-in [:ability-data :skill-exps]
                                (fn [exps]
                                  (reduce (fn [m sid] (assoc m sid 1.0))
                                          (or exps {})
                                          all-skill-ids))))
                s)))
          (log/info "Learned all" (count all-skill-ids) "nodes in category" category-id
                    "for player" (.getName (.getGameProfile player)))
          (send-feedback source "command.academy.aim.node.learn_all.success" true [] false)
          {:success? true})))))

(defmethod cmd-actions/execute-action-impl :list-learned-nodes
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (let [state (get-player-runtime-data player)
              learned (->> (get-in state [:ability-data :learned-skills] #{})
                           (map name)
                           sort)
              message (if (seq learned)
                        (str/join ", " learned)
                        "(none)")]
          (log/info "Listing learned nodes for player" (.getName (.getGameProfile player)))
          (send-feedback source "command.academy.aim.node.learned.list" true [message] false)
          {:success? true})))))

(defmethod cmd-actions/execute-action-impl :list-available-nodes
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (let [state (get-player-runtime-data player)
              category-id (get-in state [:ability-data :category-id])
              learned (get-in state [:ability-data :learned-skills] #{})
              all-skills (when category-id
                           (power-runtime/get-skills-for-category category-id))
              available-ids (->> all-skills (map :id) (filter identity) sort)
              payload (if (seq available-ids)
                        (str "category=" (name (or category-id "none"))
                             "; available=[" (str/join ", " (map name available-ids)) "]"
                             "; learned=[" (str/join ", " (sort (map name learned))) "]")
                        (str "category=" (name (or category-id "none")) "; no skills registered"))]
          (log/info "Listing available nodes for player" (.getName (.getGameProfile player)))
          (send-feedback source "command.academy.aim.node.list" true [payload] false)
          {:success? true})))))

(defmethod cmd-actions/execute-action-impl :set-level
  [action-map context]
  (let [source (:source context)
        level (:level action-map)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (-> state
                (assoc-in [:ability-data :level] (int level))
                (assoc-in [:ability-data :level-progress] 0.0))))
        (log/info "Setting level" level "for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.level.success" true [(str level)] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :set-node-exp
  [action-map context]
  (let [source (:source context)
    node-id (:node-id action-map)
        exp (:exp action-map)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (let [v (float (max 0.0 (min 1.0 (double exp))))]
          (update-player-runtime-data!
            player
            (fn [state]
              (-> state
                  (update-in [:ability-data :learned-skills] (fnil conj #{}) node-id)
                  (assoc-in [:ability-data :skill-exps node-id] v))))
          (log/info "Setting node exp" node-id "to" exp "for player" (.getName (.getGameProfile player)))
          (send-feedback source "command.academy.aim.node.exp.success" true [(name node-id) (str exp)] false)
          {:success? true})))))

(defmethod cmd-actions/execute-action-impl :restore-cp
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (-> state
                (assoc-in [:resource-data :cur-cp]
                          (double (get-in state [:resource-data :max-cp]
                                          (get-in state [:resource-data :cur-cp] 0.0))))
                (assoc-in [:resource-data :cur-overload] 0.0)
                (assoc-in [:resource-data :overload-fine] true)
                (assoc-in [:resource-data :until-recover] 0)
                (assoc-in [:resource-data :until-overload-recover] 0))))
        (log/info "Restoring CP for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.fullcp.success" true [] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :clear-cooldowns
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data! player #(assoc % :cooldown-data {}))
        (log/info "Clearing cooldowns for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.cd_clear.success" true [] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :reset-abilities
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (let [fresh (normalize-runtime-state (power-runtime/fresh-player-state))]
          (update-player-runtime-data!
            player
            (fn [state]
              (-> state
                  (assoc :ability-data (:ability-data fresh))
                  (assoc :resource-data (:resource-data fresh))
                  (assoc :cooldown-data (:cooldown-data fresh))
                  (assoc :preset-data (:preset-data fresh))
                  (assoc :develop-data (:develop-data fresh)))))
          (log/info "Resetting abilities for player" (.getName (.getGameProfile player)))
          (send-feedback source "command.academy.aim.reset.success" true [] false)
          {:success? true})))))

(defmethod cmd-actions/execute-action-impl :maxout-progression
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data!
          player
          (fn [state]
            (let [learned (get-in state [:ability-data :learned-skills] #{})]
              (-> state
                  (assoc-in [:ability-data :level] 5)
                  (assoc-in [:ability-data :level-progress] 0.0)
                  (assoc-in [:ability-data :skill-exps]
                            (reduce (fn [m sid] (assoc m sid 1.0))
                                    (or (get-in state [:ability-data :skill-exps]) {})
                                    learned))))))
        (log/info "Maxing out progression for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.maxout.success" true [] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :enable-cheats
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data! player #(assoc-in % [:terminal-data :cheats-enabled?] true))
        (log/info "Enabling cheats for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.cheats_on.success" true [] false)
        {:success? true}))))

(defmethod cmd-actions/execute-action-impl :disable-cheats
  [action-map context]
  (let [source (:source context)
        ^ServerPlayer player (:player action-map)]
    (with-command-action
      source
      (fn []
        (update-player-runtime-data! player #(assoc-in % [:terminal-data :cheats-enabled?] false))
        (log/info "Disabling cheats for player" (.getName (.getGameProfile player)))
        (send-feedback source "command.academy.aim.cheats_off.success" true [] false)
        {:success? true}))))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize command executor.

  This ensures all action implementations are loaded."
  []
  (log/info "Command executor initialized"))
