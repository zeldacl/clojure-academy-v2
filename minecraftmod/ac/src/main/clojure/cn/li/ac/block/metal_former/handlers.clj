(ns cn.li.ac.block.metal-former.handlers
	"Metal Former network handlers."
	(:require [cn.li.mcmod.network.server :as net-server]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.ac.block.metal-former.logic :as former-logic]
						[cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :metal-former action))

(defn- handle-get-status [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)]
		(if tile
			(let [state (or (platform-be/get-custom-state tile) former-logic/former-default-state)]
				{:energy (:energy state 0.0)
				 :max-energy (:max-energy state 0.0)
				 :work-counter (:work-counter state 0)
				 :mode (or (:mode state) "plate")
				 :working (:working state false)})
			{:energy 0.0 :max-energy 0.0 :work-counter 0 :mode "plate" :working false})))

(defn- handle-alternate [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				delta (int (or (:dir payload) 0))]
		(if-not tile
			{:success false}
			(let [state (or (platform-be/get-custom-state tile) former-logic/former-default-state)
						next-state (former-logic/cycle-mode state delta)]
				(platform-be/set-custom-state! tile next-state)
				(platform-be/set-changed! tile)
				{:success true
				 :mode (:mode next-state)}))))

(defn register-network-handlers! []
	(net-server/register-handler (msg :get-status) handle-get-status)
	(net-server/register-handler (msg :alternate) handle-alternate)
	(log/info "Metal Former network handlers registered"))