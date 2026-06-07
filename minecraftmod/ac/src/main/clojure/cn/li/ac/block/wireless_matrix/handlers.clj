(ns cn.li.ac.block.wireless-matrix.handlers
  "Wireless Matrix network message handlers and infrastructure."
  (:require [clojure.string :as str]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.block.machine.handlers :as machine-handlers]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

;; ============================================================================
;; Infrastructure (from network_infra.clj)
;; ============================================================================

(defn- open-tile [payload player]
  (machine-handlers/open-container-tile payload player))

(defn resolve-controller
  [be]
  (when be
    (matrix-logic/resolve-controller-be be)))

(defn matrix-wireless-cap
  [be]
  (when be
    (let [ctrl (matrix-logic/resolve-controller-be be)]
      (when ctrl
        (resolver/matrix-capability ctrl)))))

(defn- owner-authorized?
  [ctrl player]
  (let [state (matrix-logic/safe-state ctrl)]
    (matrix-logic/owner-authorized? state player)))

(defn owner-controller
  [payload player]
  (let [be (open-tile payload player)
        ctrl (resolve-controller be)]
    (when (and ctrl (owner-authorized? ctrl player))
      {:ctrl ctrl})))

(defn wireless-network
  [ctrl]
  (when ctrl
    (wireless-api/get-wireless-net-by-matrix ctrl)))

(defn create-network!
  [ctrl ssid password]
  (boolean (wireless-api/create-network! ctrl ssid password)))

(defn change-ssid!
  [network new-ssid]
  (wireless-api/change-network-ssid! network new-ssid))

(defn change-password!
  [network new-password]
  (wireless-api/change-network-password! network new-password))

;; ============================================================================
;; Response DTO builders (from network_presenter.clj)
;; ============================================================================

(defn gather-info-response
  [network cap ctrl]
  (let [{:keys [ssid password load]} (wireless-api/network-snapshot network)]
    {:ssid ssid
     :password password
     :owner (cond
              cap  (str (.getPlacerName ^IWirelessMatrix cap))
              ctrl (-> ctrl matrix-logic/safe-state matrix-logic/placer-name)
              :else "Unknown")
     :load (or load 0)
     :max-capacity (if cap (.getMatrixCapacity ^IWirelessMatrix cap) 16)
     :range (if cap (.getMatrixRange ^IWirelessMatrix cap) 64.0)
     :bandwidth (if cap (.getMatrixBandwidth ^IWirelessMatrix cap) 100)
     :initialized (boolean network)}))

;; ============================================================================
;; Message ID helper
;; ============================================================================

(defn- msg
  [action]
  (msg-registry/msg :matrix action))

;; ============================================================================
;; Internal helpers
;; ============================================================================

(defn- payload-pos
  [_payload]
  nil)

(defn- fail
  [action _payload owner-check reason]
  (log/warn "Matrix handler rejected"
            {:action action
             :owner-check owner-check
             :reason reason})
  {:success false})

(defn handle-gather-info
  [payload player]
  (let [be (open-tile payload player)
        ctrl (resolve-controller be)
        cap (matrix-wireless-cap be)
        network (wireless-network ctrl)]
    (gather-info-response network cap ctrl)))

(defn- with-owner-controller
  [action payload player f]
  (if-let [{:keys [ctrl]} (owner-controller payload player)]
    (f ctrl)
    (fail action payload false :not-owner)))

(defn handle-init-network
  [payload player]
  (with-owner-controller :init payload player
    (fn [tile]
      (let [{:keys [ssid password]} payload]
        (try
          {:success (create-network! tile ssid password)}
          (catch Exception e
            (log/error "Failed to initialize network:" {:action :init :owner-check true :pos (payload-pos payload)} (ex-message e))
            (fail :init payload true :exception)))))))

(defn handle-change-ssid
  [payload player]
  (log/info "handle-change-ssid received, payload:" (pr-str payload))
  (with-owner-controller :change-ssid payload player
    (fn [tile]
      (log/info "handle-change-ssid: owner authorized, looking up network for tile:" tile)
      (if-let [network (wireless-network tile)]
        (try
          (log/info "handle-change-ssid: changing network SSID to" (:new-ssid payload))
          {:success (change-ssid! network (:new-ssid payload))}
          (catch Exception e
            (log/error "Failed to change SSID:" {:action :change-ssid :owner-check true :pos (payload-pos payload)} (ex-message e))
            (fail :change-ssid payload true :exception)))
        (do
          (log/warn "handle-change-ssid: network not found for tile")
          (fail :change-ssid payload true :network-not-found))))))

(defn handle-change-password
  [payload player]
  (log/info "handle-change-password received, payload:" (pr-str payload))
  (with-owner-controller :change-password payload player
    (fn [tile]
      (log/info "handle-change-password: owner authorized, looking up network for tile:" tile)
      (if-let [network (wireless-network tile)]
        (try
          (log/info "handle-change-password: changing network password")
          {:success (change-password! network (:new-password payload))}
          (catch Exception e
            (log/error "Failed to change password:" {:action :change-password :owner-check true :pos (payload-pos payload)} (ex-message e))
            (fail :change-password payload true :exception)))
        (do
          (log/warn "handle-change-password: network not found for tile")
          (fail :change-password payload true :network-not-found))))))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-network-handlers!
  []
  (net-server/register-handler (msg :gather-info) handle-gather-info)
  (net-server/register-handler (msg :init) handle-init-network)
  (net-server/register-handler (msg :change-ssid) handle-change-ssid)
  (net-server/register-handler (msg :change-password) handle-change-password)
  (log/info "Matrix network handlers registered"))
