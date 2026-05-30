(ns cn.li.ac.command.actions
	"AC-owned command action manifest and executors.

	mcmod owns the generic command action registry. This namespace owns AC action
	ids and the state-shape mutations behind them. mc1201 supplies opaque platform
	adapters (for example player -> UUID and feedback callbacks) through command
	context metadata."
	(:require 
            [cn.li.ac.ability.service.player-state-dirty :as ps-dirty]
[cn.li.ac.ability.service.player-state-core :as ps-core]
[clojure.string :as str]
						[cn.li.ac.ability.model.ability :as adata]
						[cn.li.ac.ability.model.cooldown :as cdata]
						[cn.li.ac.ability.model.develop :as ddata]
						[cn.li.ac.ability.model.preset :as pdata]
						[cn.li.ac.ability.model.resource :as rdata]
								[cn.li.ac.ability.service.player-state-actions :as state-actions]						[cn.li.ac.ability.registry.skill-query :as skill-query]
						[cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
						[cn.li.mcmod.command.actions :as command-actions]
						[cn.li.mcmod.util.log :as log]))

(def ability-command-action-types
	#{:switch-category
		:learn-node
		:unlearn-node
		:learn-all-nodes
		:list-learned-nodes
		:list-available-nodes
		:set-level
		:set-node-exp
		:restore-cp
		:clear-cooldowns
		:reset-abilities
		:maxout-progression
		:enable-cheats
		:disable-cheats})

(defonce-guard command-actions-installed?)

(defn- context-metadata
	[context]
	(or (:metadata context) {}))

(defn- send-feedback!
	([context message-key]
	 (send-feedback! context message-key [] false))
	([context message-key args error?]
	 (if-let [send-feedback-fn (:send-feedback-fn (context-metadata context))]
		 (send-feedback-fn message-key true (vec args) error?)
		 (log/info "Command feedback" {:message message-key
																		:args (vec args)
																		:error? (boolean error?)}))))

(defn- platform-player->uuid
	[context player]
	(cond
		(string? player)
		player

		(keyword? player)
		(name player)

		player
		(if-let [player-uuid-fn (:player-uuid-fn (context-metadata context))]
			(player-uuid-fn player)
			(throw (ex-info "No command player UUID resolver installed" {:player player})))

		:else
		nil))

(defn- action-player-uuid
	[action-map context]
	(or (:player-uuid action-map)
			(platform-player->uuid context (:player action-map))
			(platform-player->uuid context (:target-player context))
			(platform-player->uuid context (:player context))
			(throw (ex-info "Command action requires a player" {:action (:action action-map)}))))

(defn- normalize-runtime-state
	[state]
	(let [defaults (ps-core/fresh-state)]
		(-> (merge defaults (or state {}))
				(update :ability-data #(merge (:ability-data defaults) (or % {})))
				(update :resource-data #(merge (:resource-data defaults) (or % {})))
				(update :cooldown-data #(or % (:cooldown-data defaults)))
				(update :preset-data #(merge (:preset-data defaults) (or % {})))
				(update :develop-data #(merge (:develop-data defaults) (or % {})))
				(update :terminal-data #(merge (:terminal-data defaults) (or % {}))))))

(defn- update-player-runtime-data!
	[player-uuid f]
	(let [current (normalize-runtime-state (ps-core/get-or-create-player-state! player-uuid))
				updated (normalize-runtime-state (f current))]
		(ps-core/set-player-state! player-uuid updated)
		(ps-dirty/mark-dirty! player-uuid)
		(ps-core/get-player-state player-uuid)))

(defn- skills-for-category
	[category-id]
	(when category-id
		(->> (skill-query/get-skills-for-category category-id)
				 (map :id)
				 (filter identity)
				 sort
				 vec)))

(defn- execute-switch-category!
	[action-map context]
	(let [category-id (:category-id action-map)
				player-uuid (action-player-uuid action-map context)]
		(state-actions/change-category! player-uuid category-id)
		(log/info "Switching category for player" player-uuid "to" category-id)
		(send-feedback! context "command.academy.aim.cat.success" [(name category-id)] false)
		{:success? true}))

(defn- execute-learn-node!
	[action-map context]
	(let [node-id (:node-id action-map)
				player-uuid (action-player-uuid action-map context)]
		(state-actions/learn-skill! player-uuid node-id)
		(log/info "Learning node" node-id "for player" player-uuid)
		(send-feedback! context "command.academy.aim.node.learn.success" [(name node-id)] false)
		{:success? true}))

(defn- execute-unlearn-node!
	[action-map context]
	(let [node-id (:node-id action-map)
				player-uuid (action-player-uuid action-map context)]
		(state-actions/unlearn-skill! player-uuid node-id)
		(log/info "Unlearning node" node-id "for player" player-uuid)
		(send-feedback! context "command.academy.aim.node.unlearn.success" [(name node-id)] false)
		{:success? true}))

(defn- execute-learn-all-nodes!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)]
		(let [state (normalize-runtime-state (ps-core/get-or-create-player-state! player-uuid))
					skill-ids (skills-for-category (get-in state [:ability-data :category-id]))]
			(state-actions/learn-skills! player-uuid skill-ids)
			(doseq [skill-id skill-ids]
				(state-actions/set-skill-exp! player-uuid skill-id 1.0)))
		(log/info "Learned all nodes for player" player-uuid)
		(send-feedback! context "command.academy.aim.node.learn_all.success" [] false)
		{:success? true}))

(defn- execute-list-learned-nodes!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)
				state (normalize-runtime-state (ps-core/get-or-create-player-state! player-uuid))
				learned (->> (get-in state [:ability-data :learned-skills] #{})
										 (map name)
										 sort)
				message (if (seq learned) (str/join ", " learned) "(none)")]
		(send-feedback! context "command.academy.aim.node.learned.list" [message] false)
		{:success? true}))

(defn- execute-list-available-nodes!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)
				state (normalize-runtime-state (ps-core/get-or-create-player-state! player-uuid))
				category-id (get-in state [:ability-data :category-id])
				learned (get-in state [:ability-data :learned-skills] #{})
				available-ids (->> (skills-for-category category-id)
													 (map name)
													 sort)
				payload (if (seq available-ids)
									(str "category=" (name (or category-id "none"))
											 "; available=[" (str/join ", " available-ids) "]"
											 "; learned=[" (str/join ", " (sort (map name learned))) "]")
									(str "category=" (name (or category-id "none")) "; no skills registered"))]
		(send-feedback! context "command.academy.aim.node.list" [payload] false)
		{:success? true}))

(defn- execute-set-level!
	[action-map context]
	(let [level (:level action-map)
				player-uuid (action-player-uuid action-map context)]
		(state-actions/set-level! player-uuid level)
		(log/info "Setting level" level "for player" player-uuid)
		(send-feedback! context "command.academy.aim.level.success" [(str level)] false)
		{:success? true}))

(defn- execute-set-node-exp!
	[action-map context]
	(let [node-id (:node-id action-map)
				exp (:exp action-map)
				player-uuid (action-player-uuid action-map context)]
		(state-actions/set-skill-exp! player-uuid node-id exp)
		(log/info "Setting node exp" node-id "to" exp "for player" player-uuid)
		(send-feedback! context "command.academy.aim.node.exp.success" [(name node-id) (str exp)] false)
		{:success? true}))

(defn- execute-restore-cp!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)]
		(state-actions/recover-all! player-uuid)
		(send-feedback! context "command.academy.aim.fullcp.success" [] false)
		{:success? true}))

(defn- execute-clear-cooldowns!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)]
		(state-actions/clear-cooldowns! player-uuid)
		(send-feedback! context "command.academy.aim.cd_clear.success" [] false)
		{:success? true}))

(defn- execute-reset-abilities!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)]
		(state-actions/reset-abilities! player-uuid)
		(send-feedback! context "command.academy.aim.reset.success" [] false)
		{:success? true}))

(defn- execute-maxout-progression!
	[action-map context]
	(let [player-uuid (action-player-uuid action-map context)]
		(let [state (normalize-runtime-state (ps-core/get-or-create-player-state! player-uuid))
					skill-ids (skills-for-category (get-in state [:ability-data :category-id]))]
			(state-actions/maxout-progression! player-uuid skill-ids))
		(send-feedback! context "command.academy.aim.maxout.success" [] false)
		{:success? true}))

(defn- execute-set-cheats!
	[action-map context enabled?]
	(let [player-uuid (action-player-uuid action-map context)]
		(update-player-runtime-data!
			player-uuid
			#(assoc % :cheats-enabled? (boolean enabled?)))
		(send-feedback! context (if enabled?
															"command.academy.aim.cheats_on.success"
															"command.academy.aim.cheats_off.success") [] false)
		{:success? true}))

(defn execute-ability-command-action!
	"Execute an AC-owned command action."
	[action-map context]
	(case (:action action-map)
		:switch-category (execute-switch-category! action-map context)
		:learn-node (execute-learn-node! action-map context)
		:unlearn-node (execute-unlearn-node! action-map context)
		:learn-all-nodes (execute-learn-all-nodes! action-map context)
		:list-learned-nodes (execute-list-learned-nodes! action-map context)
		:list-available-nodes (execute-list-available-nodes! action-map context)
		:set-level (execute-set-level! action-map context)
		:set-node-exp (execute-set-node-exp! action-map context)
		:restore-cp (execute-restore-cp! action-map context)
		:clear-cooldowns (execute-clear-cooldowns! action-map context)
		:reset-abilities (execute-reset-abilities! action-map context)
		:maxout-progression (execute-maxout-progression! action-map context)
		:enable-cheats (execute-set-cheats! action-map context true)
		:disable-cheats (execute-set-cheats! action-map context false)
		(throw (ex-info "Unknown AC command action" {:action (:action action-map)}))))

(defn install-command-actions!
	"Register AC command action ids and executors into the mcmod command seam."
	[]
	(with-init-guard command-actions-installed?
		(command-actions/register-action-executors!
			(zipmap ability-command-action-types
							(repeat execute-ability-command-action!)))
		(log/info "AC command actions installed" {:actions ability-command-action-types}))
	nil)


