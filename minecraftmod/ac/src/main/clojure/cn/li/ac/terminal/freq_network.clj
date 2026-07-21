(ns cn.li.ac.terminal.freq-network
  "Server-side network handlers for the Frequency Transmitter app.
  Handles ray-trace device scanning and wireless configuration commands."
  (:require [cn.li.ac.wireless.api :as wireless]
            [cn.li.ac.wireless.core.capability-resolver :as cap-resolver]
            [cn.li.ac.wireless.feedback :as feedback]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix IWirelessNode]))

;; Message IDs (same as client-side)
(def freq-scan-msg   1005)
(def freq-config-msg 1006)

;; --- Target resolution ---

(defn- resolve-hit-target
  "Ray-trace from the player's look direction. Returns {:world :pos :tile} or nil."
  [player]
  (when-let [hit (entity/player-raytrace-block player 4.0 false)]
    (let [world (entity/player-get-level player)
          hx (:x (:hit-pos hit)) hy (:y (:hit-pos hit)) hz (:z (:hit-pos hit))
          bp (pos/create-block-pos hx hy hz)]
      {:world world :pos {:x hx :y hy :z hz} :tile (platform-be/get-block-entity world bp)})))

(defn- resolve-target
  "Resolve the target tile from a remembered scan position (payload :pos) when
  present, falling back to a fresh ray-trace. Preferring the remembered
  position avoids acting on a different block if the player's aim drifts by
  even a pixel between the scan and configure/link/unlink steps."
  [player payload]
  (if-let [p (:pos payload)]
    (let [world (entity/player-get-level player)
          bp (pos/create-block-pos (:x p) (:y p) (:z p))]
      {:world world :pos p :tile (platform-be/get-block-entity world bp)})
    (resolve-hit-target player)))

;; --- Scan handler ---

(defn- handle-scan
  "Ray-trace from the player's look direction to find a wireless device.
  Returns device info (type, SSID, password, position) if found."
  [_payload player]
  (try
    (if-let [{:keys [pos tile]} (resolve-hit-target player)]
      (let [matrix-cap (cap-resolver/matrix-capability tile)
            node-cap  (cap-resolver/node-capability tile)]
        (cond
          matrix-cap
          (let [network (wireless/get-wireless-net-by-matrix tile)
                ssid (if network
                       (wireless/network-ssid network)
                       (.getSsid ^IWirelessMatrix matrix-cap))]
            {:success true
             :device {:type :matrix
                      :pos pos
                      :ssid (or ssid "")
                      :password (.getPassword ^IWirelessMatrix matrix-cap)
                      :has-network (some? network)
                      :node-count (if network (wireless/network-load network) 0)}})

          node-cap
          ;; :ssid/:password here describe the TARGET matrix network this node
          ;; should join (upstream's matrix-authorize-then-link-node flow) —
          ;; not the node's own name/password, which are irrelevant to linking.
          ;; Pre-fill with the currently-linked network's SSID so re-linking
          ;; shows what's already connected; leave blank when unlinked.
          (let [network (wireless/get-wireless-net-by-node tile)]
            {:success true
             :device {:type :node
                      :pos pos
                      :node-name (.getNodeName ^IWirelessNode node-cap)
                      :ssid (if network (wireless/network-ssid network) "")
                      :linked? (some? network)}})

          :else
          {:success false :error "Target block is not a wireless device"}))
      {:success false :error "No block targeted"})
    (catch Throwable e
      (log/error "Error in freq scan handler:" (ex-message e))
      {:success false :error (ex-message e)})))

;; --- Config handler ---

(defn- handle-configure
  "For a matrix: rename SSID / change password of its existing network.
  For a node: link it into the network named `ssid` (authenticating with
  `password`, matching upstream's matrix-authorize-then-link-node flow) —
  or, when :unlink? is set, disconnect it from its current network."
  [payload player]
  (try
    (let [{:keys [ssid password unlink?]} payload]
      (if-let [{:keys [world tile]} (resolve-target player payload)]
        (cond
          (cap-resolver/matrix-capability tile)
          (let [network (wireless/get-wireless-net-by-matrix tile)]
            (if network
              (if (and (string? ssid) (string? password))
                (do
                  (wireless/change-network-ssid! network ssid)
                  (wireless/change-network-password! network password)
                  {:success true
                   :message "Network configuration updated successfully"})
                {:success false :error "SSID and password are required"})
              {:success false :error "Matrix does not own a network"}))

          (cap-resolver/node-capability tile)
          (if unlink?
            (let [result (wireless/unlink-node-from-network! tile)]
              (assoc result :messages (feedback/result->messages :node result)))
            (if (and (string? ssid) (string? password))
              (let [result (wireless/connect-node-to-ssid! world tile ssid password)]
                (assoc result :messages (feedback/result->messages :node result)))
              {:success false :error "SSID and password are required"}))

          :else
          {:success false :error "No wireless device targeted"})
        {:success false :error "No block targeted"}))
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
