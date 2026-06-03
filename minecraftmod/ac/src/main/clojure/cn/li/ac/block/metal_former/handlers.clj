(ns cn.li.ac.block.metal-former.handlers
	"Metal Former network handlers."
	(:require [cn.li.mcmod.network.server :as net-server]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.wireless.gui.sync.handler :as net-helpers]
						[cn.li.ac.block.machine.handlers :as machine-handlers]
						[cn.li.ac.block.machine.runtime :as machine-runtime]
						[cn.li.ac.block.metal-former.logic :as former-logic]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.util.log :as log]))

(defn- msg [action] (msg-registry/msg :metal-former action))

(defn- handle-get-status [payload player]
	(machine-handlers/tile-status-response payload player former-logic/former-default-state
		(fn [state]
			{:energy (:energy state 0.0)
			 :max-energy (:max-energy state 0.0)
			 :work-counter (:work-counter state 0)
			 :mode (or (:mode state) "plate")
			 :working (:working state false)})))

(defn- handle-alternate [payload player]
	(let [world (net-helpers/get-world player)
				tile (net-helpers/get-tile-at world payload)
				delta (int (or (:dir payload) 0))]
		(if-not tile
			{:success false}
			(let [state (or (platform-be/get-custom-state tile) former-logic/former-default-state)
						next-state (former-logic/cycle-mode state delta)]
				(machine-runtime/commit-from-tile! tile former-logic/former-default-state next-state)
				{:success true
				 :mode (:mode next-state)}))))

(defn register-network-handlers! []
	(net-server/register-handler (msg :get-status) handle-get-status)
	(net-server/register-handler (msg :alternate) handle-alternate)
	(log/info "Metal Former network handlers registered"))