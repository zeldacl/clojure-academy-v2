(ns cn.li.ac.block.ability-interferer.handlers
	"Ability Interferer network handlers."
	(:require [clojure.string :as str]
						[cn.li.mcmod.network.server :as net-server]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.block.machine.handlers :as machine-handlers]
						[cn.li.ac.block.machine.runtime :as machine-runtime]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.ac.block.ability-interferer.logic :as interferer-logic]
						[cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :ability-interferer action))

(defn- normalize-whitelist
	[names]
	(->> names
			 (map #(str/trim (str %)))
			 (remove str/blank?)
			 distinct
			 sort
			 vec))

(defn- handle-get-status [payload player]
	(machine-handlers/tile-status-response payload player interferer-logic/interferer-default-state
		(fn [state]
			{:energy (:energy state 0.0)
			 :max-energy (:max-energy state 0.0)
			 :range (:range state 0.0)
			 :enabled (:enabled state false)
			 :placer-name (:placer-name state "")
			 :whitelist (:whitelist state [])
			 :affected-player-count (:affected-player-count state 0)})))

(defn- handle-change-range [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				requested (:range payload)]
		(if (and tile (number? requested))
			(let [state' (assoc (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
			                    :range (interferer-logic/clamp-range requested))]
				(machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state state'
				                                   :blockstate-updater interferer-logic/interferer-blockstate-updater)
				{:success true :range (:range state')})
			{:success false})))

(defn- handle-toggle-enabled [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				new-enabled (boolean (:enabled payload))]
		(if tile
			(let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
						state' (if new-enabled
						         (assoc state :enabled true)
						         (assoc state :enabled false :affected-player-count 0 :affected-player-uuids []))]
				(machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state state'
				                                   :blockstate-updater interferer-logic/interferer-blockstate-updater
				                                   :after-commit! interferer-logic/interferer-after-commit!)
				{:success true :enabled (:enabled state')})
			{:success false})))

(defn- handle-set-whitelist [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				names (:whitelist payload)]
		(if (and tile (sequential? names))
			(let [cleaned (normalize-whitelist names)
			      state' (assoc (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
			                    :whitelist cleaned)]
				(machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state state'
				                                   :blockstate-updater interferer-logic/interferer-blockstate-updater)
				{:success true :whitelist cleaned})
			{:success false})))

(defn- handle-add-to-whitelist [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				player-name (:player-name payload)]
		(if (and tile (not (str/blank? (str player-name))))
			(let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
						new-whitelist (normalize-whitelist (conj (vec (:whitelist state [])) player-name))]
				(machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state
				                                   (assoc state :whitelist new-whitelist)
				                                   :blockstate-updater interferer-logic/interferer-blockstate-updater)
				{:success true :whitelist new-whitelist})
			{:success false})))

(defn- handle-remove-from-whitelist [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				player-name (:player-name payload)]
		(if (and tile (not (str/blank? (str player-name))))
			(let [state (or (platform-be/get-custom-state tile) interferer-logic/interferer-default-state)
						new-whitelist (normalize-whitelist (remove #(= % player-name) (:whitelist state [])))]
				(machine-runtime/commit-from-tile! tile interferer-logic/interferer-default-state
				                                   (assoc state :whitelist new-whitelist)
				                                   :blockstate-updater interferer-logic/interferer-blockstate-updater)
				{:success true :whitelist new-whitelist})
			{:success false})))

(defn register-network-handlers! []
	(net-server/register-handler (msg :get-status) handle-get-status)
	(net-server/register-handler (msg :change-range) handle-change-range)
	(net-server/register-handler (msg :toggle-enabled) handle-toggle-enabled)
	(net-server/register-handler (msg :set-whitelist) handle-set-whitelist)
	(net-server/register-handler (msg :add-to-whitelist) handle-add-to-whitelist)
	(net-server/register-handler (msg :remove-from-whitelist) handle-remove-from-whitelist)
	(log/info "Ability Interferer network handlers registered"))