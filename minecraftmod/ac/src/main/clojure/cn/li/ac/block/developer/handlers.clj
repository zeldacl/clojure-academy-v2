(ns cn.li.ac.block.developer.handlers
	(:require [clojure.string :as str]
						[cn.li.mcmod.network.server :as net-server]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.ac.wireless.api :as wapi]
						[cn.li.ac.wireless.service.node-connection :as node-connection]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.block.developer.logic :as dev-logic]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessNode]))

(defn- msg [action] (msg-registry/msg :developer action))

(defn- node->info [^IWirelessNode node]
	(when node
		(let [p (try (.getBlockPos node) (catch Exception _ nil))
					pw (try (str (.getPassword node)) (catch Exception _ ""))]
			{:node-name (try (str (.getNodeName node)) (catch Exception _ "Node"))
			 :pos-x (when p (pos/pos-x p))
			 :pos-y (when p (pos/pos-y p))
			 :pos-z (when p (pos/pos-z p))
			 :is-encrypted? (not (str/blank? pw))})))

(defn- get-linked-node-for-receiver [tile]
	(when-let [conn (try (wapi/get-node-conn-by-receiver tile) (catch Exception _ nil))]
		(try (node-connection/get-node conn) (catch Exception _ nil))))

(defn handle-get-status [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		(if tile
			(let [state (or (platform-be/get-custom-state tile) dev-logic/dev-default-state)
						linked-node (get-linked-node-for-receiver tile)]
				{:energy (:energy state 0.0)
				 :max-energy (:max-energy state 50000.0)
				 :tier (:tier state "normal")
				 :user-uuid (:user-uuid state "")
				 :user-name (:user-name state "")
				 :development-progress (:development-progress state 0.0)
				 :is-developing (:is-developing state false)
				 :structure-valid (:structure-valid state false)
				 :linked (some-> linked-node node->info)
				 :avail []})
			{:energy 0.0 :max-energy 0.0 :tier "normal" :user-uuid "" :user-name ""
			 :development-progress 0.0 :is-developing false :structure-valid false
			 :linked nil :avail []})))

(defn handle-start-development [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		(if-not tile
			{:success false :reason "no-tile"}
			(let [state (or (platform-be/get-custom-state tile) dev-logic/dev-default-state)
						pid (str (entity/player-get-uuid player))
						holder (str (:user-uuid state ""))]
				(cond
					(not (:structure-valid state false)) {:success false :reason "invalid-structure"}
					(and (not (str/blank? holder)) (not= holder pid)) {:success false :reason "wrong-user"}
					:else
					(do
						(platform-be/set-custom-state! tile (assoc state :is-developing true :user-uuid pid :user-name (entity/player-get-name player)))
						(platform-be/set-changed! tile)
						{:success true}))))))

(defn handle-stop-development [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		(if tile
			(do
				(platform-be/set-custom-state! tile (assoc (or (platform-be/get-custom-state tile) dev-logic/dev-default-state) :is-developing false))
				(platform-be/set-changed! tile)
				{:success true})
			{:success false})))

(defn handle-list-nodes [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		(if tile
			(let [tile-pos (pos/position-get-block-pos tile)
						linked-node (get-linked-node-for-receiver tile)
						linked-pos (when linked-node (try (.getBlockPos ^IWirelessNode linked-node) (catch Exception _ nil)))
						nodes (if tile-pos (wapi/get-nodes-in-range world tile-pos) [])
						avail (->> nodes
											 (remove (fn [^IWirelessNode n]
																 (let [p (try (.getBlockPos n) (catch Exception _ nil))]
																	 (and p linked-pos
																				(= (pos/pos-x p) (pos/pos-x linked-pos))
																				(= (pos/pos-y p) (pos/pos-y linked-pos))
																				(= (pos/pos-z p) (pos/pos-z linked-pos))))))
											 (mapv node->info))]
				{:linked (node->info linked-node) :avail avail})
			{:linked nil :avail []})))

(defn handle-connect [payload player]
	(let [world (net-helpers/get-world player)
				recv (net-helpers/get-tile-at world payload)
				node-pos (select-keys payload [:node-x :node-y :node-z])
				pass (:password payload "")
				need-auth? (boolean (:need-auth? payload true))]
		(if (and world recv (every? number? (vals node-pos)))
			(if-let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
																									 :pos-y (:node-y node-pos)
																									 :pos-z (:node-z node-pos)})]
				{:success (boolean (wapi/link-receiver-to-node! recv node pass need-auth?))}
				{:success false})
			{:success false})))

(defn handle-disconnect [payload player]
	(let [world (net-helpers/get-world player)
				recv (net-helpers/get-tile-at world payload)]
		(if (and world recv)
			(do (wapi/unlink-receiver-from-node! recv) {:success true})
			{:success false})))

(defn register-network-handlers! []
	(net-server/register-handler (msg :get-status) handle-get-status)
	(net-server/register-handler (msg :start-development) handle-start-development)
	(net-server/register-handler (msg :stop-development) handle-stop-development)
	(net-server/register-handler (msg :list-nodes) handle-list-nodes)
	(net-server/register-handler (msg :connect) handle-connect)
	(net-server/register-handler (msg :disconnect) handle-disconnect)
	(log/info "Developer network handlers registered"))