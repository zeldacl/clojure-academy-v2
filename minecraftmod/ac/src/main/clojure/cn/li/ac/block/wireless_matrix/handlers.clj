(ns cn.li.ac.block.wireless-matrix.handlers
	"Wireless Matrix network handlers."
	(:require [cn.li.mcmod.network.server :as net-server]
						[cn.li.ac.wireless.gui.message.registry :as msg-registry]
						[cn.li.ac.block.wireless-matrix.network-infra :as infra]
						[cn.li.ac.block.wireless-matrix.network-presenter :as presenter]
						[cn.li.mcmod.util.log :as log]))

(defn- msg [action]
	(msg-registry/msg :matrix action))

(defn- payload-pos
	[payload]
	(select-keys payload [:pos-x :pos-y :pos-z]))

(defn- fail
	[action payload owner-check reason]
	(log/warn "Matrix handler rejected"
			  {:action action
			   :owner-check owner-check
			   :pos (payload-pos payload)
			   :reason reason})
	{:success false})

(defn handle-gather-info [payload player]
	(let [{:keys [be]} (infra/resolve-world-tile payload player)
				ctrl (infra/resolve-controller be)
				cap (infra/matrix-wireless-cap be)
				network (infra/wireless-network ctrl)]
		(presenter/gather-info-response network cap)))

(defn- with-owner-controller [action payload player f]
	(if-let [{:keys [ctrl]} (infra/owner-controller payload player)]
		(f ctrl)
		(fail action payload false :not-owner)))

(defn handle-init-network [payload player]
	(with-owner-controller :init payload player
		(fn [tile]
			(let [{:keys [ssid password]} payload]
				(try
					{:success (infra/create-network! tile ssid password)}
					(catch Exception e
						(log/error "Failed to initialize network:" {:action :init :owner-check true :pos (payload-pos payload)} (ex-message e))
						(fail :init payload true :exception)))))))

(defn handle-change-ssid [payload player]
	(with-owner-controller :change-ssid payload player
		(fn [tile]
			(if-let [network (infra/wireless-network tile)]
				(try
					{:success (infra/change-ssid! network (:new-ssid payload))}
					(catch Exception e
						(log/error "Failed to change SSID:" {:action :change-ssid :owner-check true :pos (payload-pos payload)} (ex-message e))
						(fail :change-ssid payload true :exception)))
				(fail :change-ssid payload true :network-not-found)))))

(defn handle-change-password [payload player]
	(with-owner-controller :change-password payload player
		(fn [tile]
			(if-let [network (infra/wireless-network tile)]
				(try
					{:success (infra/change-password! network (:new-password payload))}
					(catch Exception e
						(log/error "Failed to change password:" {:action :change-password :owner-check true :pos (payload-pos payload)} (ex-message e))
						(fail :change-password payload true :exception)))
				(fail :change-password payload true :network-not-found)))))

(defn register-network-handlers! []
	(net-server/register-handler (msg :gather-info) handle-gather-info)
	(net-server/register-handler (msg :init) handle-init-network)
	(net-server/register-handler (msg :change-ssid) handle-change-ssid)
	(net-server/register-handler (msg :change-password) handle-change-password)
	(log/info "Matrix network handlers registered"))