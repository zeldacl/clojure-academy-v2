(ns cn.li.mc1201.command.executor-core
  "Command action executor core - platform-agnostic business logic for command actions.

  This namespace provides implementations for all command actions using callbacks
  for platform-specific operations (feedback sending, etc). Vanilla Minecraft
  advancement APIs are also handled here because they are loader-agnostic.
  Both Forge and Fabric delegate to this core after setting up platform context."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  (:import [net.minecraft.advancements Advancement AdvancementProgress]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.server.level ServerPlayer]))

;; ============================================================================
;; Default Configuration
;; ============================================================================

(def ^:private default-ability-data
  {:category-id nil
   :learned-skills #{}
   :skill-exps {}
   :level 1
   :level-progress 0.0})

;; ============================================================================
;; Helper Functions (Platform-Agnostic)
;; ============================================================================

(defn- player-uuid
  "Extract UUID from player object (platform-specific call wrapped at caller)"
  [player-uuid-string]
  (str player-uuid-string))

(defn- normalize-runtime-state
  "Normalize and merge runtime state with defaults."
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
  "Update player runtime data using a state transformation function.
  
  Args:
    player-uuid-string: String UUID of player
    f: Function that transforms current state to new state
    mark-dirty-fn: Platform callback to mark player as dirty
  
  Returns:
    Updated state map"
  [player-uuid-string f mark-dirty-fn]
  (let [uuid player-uuid-string
        current (normalize-runtime-state (power-runtime/get-or-create-player-state! uuid))
        updated (normalize-runtime-state (f current))]
    (power-runtime/set-player-state! uuid updated)
    (mark-dirty-fn uuid)
    updated))

(defn- with-command-action
  "Execute a command action with error handling.
  
  Args:
    send-feedback-fn: Platform callback for sending feedback
    f: Function to execute
  
  Returns:
    Result map with :success? and optional :message"
  [send-feedback-fn f]
  (try
    (f)
    (catch Exception e
      (log/error "Command action execution failed:" (ex-message e))
      (send-feedback-fn (str "Error: " (ex-message e)) false [] true)
      {:success? false :message (ex-message e)})))

(defn get-player-runtime-data
  "Get runtime data for a player (pure data access).
  
  Args:
    player-uuid-string: String UUID of player
  
  Returns:
    RuntimeData map or nil"
  [player-uuid-string]
  (normalize-runtime-state
    (power-runtime/get-or-create-player-state! (player-uuid player-uuid-string))))

(defn set-player-runtime-data!
  "Set runtime data for a player.
  
  Args:
    player-uuid-string: String UUID of player
    runtime-data: RuntimeData map
    mark-dirty-fn: Platform callback to mark player as dirty
  
  Returns:
    true"
  [player-uuid-string runtime-data mark-dirty-fn]
  (let [uuid (player-uuid player-uuid-string)]
    (power-runtime/set-player-state! uuid (normalize-runtime-state runtime-data))
    (mark-dirty-fn uuid)
    true))

;; ============================================================================
;; Action Implementations (Platform-Agnostic Core)
;; ============================================================================

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
          (send-feedback-fn "command.academy.acach.not_found" true [advancement-id] true)
          {:success? false :message "Advancement not found"})
        (let [player-advancements (.getAdvancements player)
              ^AdvancementProgress progress (.getOrStartProgress player-advancements advancement)]
          (doseq [criterion (.getRemainingCriteria progress)]
            (.award player-advancements advancement criterion))
          (send-feedback-fn "command.academy.acach.success" true
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

(defn execute-switch-category-action
  "Execute :switch-category action.
  
  Args:
    action-map: Action data containing :category-id, :player-uuid
    send-feedback-fn: Platform callback
    mark-dirty-fn: Platform callback for marking player as dirty
  
  Returns:
    {:success? bool}"
  [action-map send-feedback-fn mark-dirty-fn]
  (let [category-id (:category-id action-map)
        player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (update-player-runtime-data!
          player-uuid
          (fn [state]
            (assoc-in state [:ability-data]
                      (assoc default-ability-data :category-id category-id)))
          mark-dirty-fn)
        (log/info "Switching category for player" player-uuid "to" category-id)
        (send-feedback-fn "command.academy.aim.cat.success" true [(name category-id)] false)
        {:success? true}))))

(defn execute-learn-node-action
  "Execute :learn-node action."
  [action-map send-feedback-fn mark-dirty-fn]
  (let [node-id (:node-id action-map)
        player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (update-player-runtime-data!
          player-uuid
          (fn [state]
            (-> state
                (update-in [:ability-data :learned-skills] (fnil conj #{}) node-id)
                (update-in [:ability-data :skill-exps]
                           (fn [exps]
                             (if (contains? (or exps {}) node-id)
                               exps
                               (assoc (or exps {}) node-id 0.0))))))
          mark-dirty-fn)
        (log/info "Learning node" node-id "for player" player-uuid)
        (send-feedback-fn "command.academy.aim.node.learn.success" true [(name node-id)] false)
        {:success? true}))))

(defn execute-unlearn-node-action
  "Execute :unlearn-node action."
  [action-map send-feedback-fn mark-dirty-fn]
  (let [node-id (:node-id action-map)
        player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (update-player-runtime-data!
          player-uuid
          (fn [state]
            (-> state
                (update-in [:ability-data :learned-skills] disj node-id)
                (update-in [:ability-data :skill-exps] dissoc node-id)))
          mark-dirty-fn)
        (log/info "Unlearning node" node-id "for player" player-uuid)
        (send-feedback-fn "command.academy.aim.node.unlearn.success" true [(name node-id)] false)
        {:success? true}))))

(defn execute-learn-all-nodes-action
  "Execute :learn-all-nodes action."
  [action-map send-feedback-fn mark-dirty-fn]
  (let [player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (let [state (get-player-runtime-data player-uuid)
              category-id (get-in state [:ability-data :category-id])
              all-skill-ids (when category-id
                              (->> (power-runtime/get-skills-for-category category-id)
                                   (map :id)
                                   (filter identity)
                                   set))
              _ (when (empty? all-skill-ids)
                  (log/warn "learn-all-nodes: no skills found for category" category-id))]
          (update-player-runtime-data!
            player-uuid
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
                s))
            mark-dirty-fn)
          (log/info "Learned all" (count all-skill-ids) "nodes in category" category-id
                    "for player" player-uuid)
          (send-feedback-fn "command.academy.aim.node.learn_all.success" true [] false)
          {:success? true})))))

(defn execute-list-learned-nodes-action
  "Execute :list-learned-nodes action."
  [action-map send-feedback-fn _mark-dirty-fn]
  (let [player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (let [state (get-player-runtime-data player-uuid)
              learned (->> (get-in state [:ability-data :learned-skills] #{})
                           (map name)
                           sort)
              message (if (seq learned)
                        (str/join ", " learned)
                        "(none)")]
          (log/info "Listing learned nodes for player" player-uuid)
          (send-feedback-fn "command.academy.aim.node.learned.list" true [message] false)
          {:success? true})))))

(defn execute-list-available-nodes-action
  "Execute :list-available-nodes action."
  [action-map send-feedback-fn _mark-dirty-fn]
  (let [player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (let [state (get-player-runtime-data player-uuid)
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
          (log/info "Listing available nodes for player" player-uuid)
          (send-feedback-fn "command.academy.aim.node.list" true [payload] false)
          {:success? true})))))

(defn execute-set-level-action
  "Execute :set-level action."
  [action-map send-feedback-fn mark-dirty-fn]
  (let [level (:level action-map)
        player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (update-player-runtime-data!
          player-uuid
          (fn [state]
            (-> state
                (assoc-in [:ability-data :level] (int level))
                (assoc-in [:ability-data :level-progress] 0.0)))
          mark-dirty-fn)
        (log/info "Setting level" level "for player" player-uuid)
        (send-feedback-fn "command.academy.aim.level.success" true [(str level)] false)
        {:success? true}))))

(defn execute-set-node-exp-action
  "Execute :set-node-exp action."
  [action-map send-feedback-fn mark-dirty-fn]
  (let [node-id (:node-id action-map)
        exp (:exp action-map)
        player-uuid (:player-uuid action-map)]
    (with-command-action
      send-feedback-fn
      (fn []
        (update-player-runtime-data!
          player-uuid
          (fn [state]
            (assoc-in state [:ability-data :skill-exps node-id] (float exp)))
          mark-dirty-fn)
        (log/info "Setting node exp" node-id "to" exp "for player" player-uuid)
        (send-feedback-fn "command.academy.aim.node.exp.success" true [(name node-id) (str exp)] false)
        {:success? true}))))
