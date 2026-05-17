(ns cn.li.ac.block.solar-gen.handlers
	(:require [cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.network.server :as net-server]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.wireless.api :as wireless-api]
						[cn.li.ac.block.solar-gen.logic :as solar-logic]
						[cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :generator action))

(defn- handle-get-status [payload player]
	(let [world (net-helpers/get-world player)
				tile  (net-helpers/get-tile-at world payload)]
		{:linked (some-> tile solar-logic/get-linked-node solar-logic/node->info) :avail []}))

(defn- handle-list-nodes [payload player]
	(let [world (net-helpers/get-world player)
				tile  (net-helpers/get-tile-at world payload)]
		(if tile
			(let [tile-pos    (pos/position-get-block-pos tile)
						linked-node (solar-logic/get-linked-node tile)
						linked-pos  (when linked-node (.getBlockPos ^cn.li.acapi.wireless.IWirelessNode linked-node))
						nodes       (if tile-pos (wireless-api/get-nodes-in-range world tile-pos) [])
						avail       (->> nodes
															(remove (fn [^cn.li.acapi.wireless.IWirelessNode n]
																			 (let [p (.getBlockPos n)]
																				 (and p linked-pos
																							(= (pos/pos-x p) (pos/pos-x linked-pos))
																							(= (pos/pos-y p) (pos/pos-y linked-pos))
																							(= (pos/pos-z p) (pos/pos-z linked-pos))))))
														 (mapv solar-logic/node->info))]
				{:linked (solar-logic/node->info linked-node) :avail avail})
			{:linked nil :avail []})))

(defn- handle-connect [payload player]
	(let [world      (net-helpers/get-world player)
				gen        (net-helpers/get-tile-at world payload)
				node-pos   (select-keys payload [:node-x :node-y :node-z])
				pass       (:password payload "")
				need-auth? (boolean (:need-auth? payload true))]
		(if (and world gen (every? number? (vals node-pos)))
			(if-let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
																										:pos-y (:node-y node-pos)
																										:pos-z (:node-z node-pos)})]
				{:success (boolean (wireless-api/link-generator-to-node! gen node pass need-auth?))}
				{:success false})
			{:success false})))

(defn- handle-disconnect [payload player]
	(let [world (net-helpers/get-world player)
				gen   (net-helpers/get-tile-at world payload)]
		(if (and world gen)
			(do (wireless-api/unlink-generator-from-node! gen)
					{:success true})
			{:success false})))

(defn register-network-handlers! []
	(net-server/register-handler (msg :get-status) handle-get-status)
	(net-server/register-handler (msg :list-nodes) handle-list-nodes)
	(net-server/register-handler (msg :connect) handle-connect)
	(net-server/register-handler (msg :disconnect) handle-disconnect)
	(log/info "Solar Generator network handlers registered"))