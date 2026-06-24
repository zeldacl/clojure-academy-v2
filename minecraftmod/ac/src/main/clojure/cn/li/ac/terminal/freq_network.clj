(ns cn.li.ac.terminal.freq-network
  "Server-side network handlers for the Frequency Transmitter app.
  Handles ray-trace device scanning and wireless configuration commands."
  (:require [cn.li.ac.wireless.api :as wireless]
            [cn.li.ac.wireless.core.capability-resolver :as cap-resolver]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

;; Message IDs (same as client-side)
(def freq-scan-msg   1005)
(def freq-config-msg 1006)

;; --- Scan handler ---

(defn- handle-scan
  "Ray-trace from the player's look direction to find a wireless device.
  Returns device info (type, SSID, password) if found."
  [_payload player]
  (try
    (let [hit (entity/player-raytrace-block player 4.0 false)]
      (if hit
        (let [world (entity/player-get-level player)
              hit-bp (pos/create-block-pos (:x (:hit-pos hit))
                                           (:y (:hit-pos hit))
                                           (:z (:hit-pos hit)))
              tile (platform-be/get-block-entity world hit-bp)
              matrix-cap (cap-resolver/matrix-capability tile)
              node-cap  (cap-resolver/node-capability tile)]
          (cond
            matrix-cap
            (let [network (wireless/get-wireless-net-by-matrix tile)
                  ssid (if network
                         (wireless/network-ssid network)
                         (.getSsid ^IWirelessMatrix matrix-cap))]
              {:success true
               :device {:type :matrix
                        :ssid (or ssid "")
                        :password (.getPassword ^IWirelessMatrix matrix-cap)
                        :has-network (some? network)
                        :node-count (if network (wireless/network-load network) 0)}})

            node-cap
            (let [network (wireless/get-wireless-net-by-node tile)
                  ssid (if network
                         (wireless/network-ssid network)
                         (.getNodeName ^IWirelessNode node-cap))]
              {:success true
               :device {:type :node
                        :ssid (or ssid "")
                        :password (.getPassword ^IWirelessNode node-cap)
                        :linked? (wireless/is-node-linked? tile)}})

            :else
            {:success false :error "Target block is not a wireless device"}))
        {:success false :error "No block targeted"}))
    (catch Throwable e
      (log/error "Error in freq scan handler:" (ex-message e))
      {:success false :error (ex-message e)})))

;; --- Config handler ---

(defn- handle-configure
  "Change SSID and/or password of a wireless device.
  The device was previously identified by scan; config is applied via
  wireless API commands."
  [payload player]
  (try
    (let [{:keys [ssid password]} payload]
      (if (and (string? ssid) (string? password))
        ;; Simplified: attempt to update the first network/device found
        ;; via ray-trace. In a full implementation, device identity would
        ;; be preserved from the scan phase.
        (let [hit (entity/player-raytrace-block player 4.0 false)]
          (if hit
            (let [world (entity/player-get-level player)
                  hit-bp (pos/create-block-pos (:x (:hit-pos hit))
                                               (:y (:hit-pos hit))
                                               (:z (:hit-pos hit)))
                  tile (platform-be/get-block-entity world hit-bp)]
              (cond
                (cap-resolver/matrix-capability tile)
                (let [network (wireless/get-wireless-net-by-matrix tile)]
                  (if network
                    (do
                      (wireless/change-network-ssid! network ssid)
                      (wireless/change-network-password! network password)
                      {:success true
                       :message "Network configuration updated successfully"})
                    {:success false :error "Matrix does not own a network"}))
                (cap-resolver/node-capability tile)
                {:success false :error "Node password changes not yet implemented"}
                :else
                {:success false :error "No wireless device targeted"}))
            {:success false :error "No block targeted"}))
        {:success false :error "SSID and password are required"}))
    (catch Throwable e
      (log/error "Error in freq config handler:" (ex-message e))
      {:success false :error (ex-message e)})))

;; --- Registration ---

(defn register-handlers!
  "Register freq transmitter server-side network handlers."
  []
  (net-server/register-handler freq-scan-msg handle-scan)
  (net-server/register-handler freq-config-msg handle-configure)
  (log/info "Freq transmitter network handlers registered"))
