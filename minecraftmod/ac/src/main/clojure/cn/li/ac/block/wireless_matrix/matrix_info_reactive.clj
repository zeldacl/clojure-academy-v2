(ns cn.li.ac.block.wireless-matrix.matrix-info-reactive
  "Reactive info-area for Wireless Matrix — SSID/password editing + INIT form.
   Network-info policy/messaging lives here since this namespace is its only
   consumer."
  (:require [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.gui.info-area-reactive :as info-area]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

;; ============================================================================
;; Data — network info
;; ============================================================================

(defrecord MatrixNetworkData
  [ssid password owner load max-capacity range bandwidth initialized])

(defn network-initialized? [data]
  (:initialized data))

(defn matrix-info-area-policy
  "Return Matrix info-area interaction policy.

  Keys:
  - :show-init?          show initialization form
  - :show-noinit?        show not-initialized message only
  - :editable-ssid?      whether SSID is editable
  - :editable-password?  whether password is editable"
  [initialized? is-owner?]
  (let [owner? (true? is-owner?)]
    (cond
      (true? initialized?)
      {:show-init? false
       :show-noinit? false
       :editable-ssid? owner?
       :editable-password? owner?}

      (and (or (false? initialized?) (nil? initialized?)) owner?)
      {:show-init? true
       :show-noinit? false
       :editable-ssid? false
       :editable-password? false}

      (or (false? initialized?) (nil? initialized?))
      {:show-init? false
       :show-noinit? true
       :editable-ssid? false
       :editable-password? false}

      :else
      {:show-init? false
       :show-noinit? false
       :editable-ssid? false
       :editable-password? false})))

;; ============================================================================
;; Network Messages
;; ============================================================================

(defn- msg
  "Generate message ID for matrix actions (must match server DSL / underscores)."
  [action]
  (msg-registry/msg :matrix action))

(defn- current-client-owner
  "Resolve a client owner for GUI-initiated server requests.
  Uses the platform-registered hook to get the client owner,
  which matches the source used by with-client-response-owner during response dispatch."
  []
  (runtime-hooks/default-client-owner))

(defn send-gather-info
  "Query network information from server."
  [container callback]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :gather-info)
        (action-payload/action-payload container {})
        (fn [response]
          (try
            (let [data (map->MatrixNetworkData
                         {:ssid (get response :ssid)
                          :password (get response :password)
                          :owner (get response :owner "Unknown")
                          :load (get response :load 0)
                          :max-capacity (get response :max-capacity 16)
                          :range (get response :range 64)
                          :bandwidth (get response :bandwidth 100)
                          :initialized (boolean (if (contains? response :initialized)
                                                  (get response :initialized)
                                                  (get response :ssid)))})]
              (callback data))
            (catch Exception e
              (log/error "Error processing gather-info response:"(ex-message e))))))
      (log/info "[send-gather-info] Skip: current-client-owner returned nil"))
    (catch Exception e
      (log/error "Error sending gather-info:"(ex-message e)))))

(defn send-init-network
  [container ssid password callback]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :init)
        (action-payload/action-payload container {:ssid ssid :password password})
        (fn [response]
          (try
            (callback (get response :success false))
            (catch Exception e
              (log/error "Error processing init response:"(ex-message e))))))
      (log/info "[send-init-network] Skip: current-client-owner returned nil"))
    (catch Exception e
      (log/error "Error sending init:"(ex-message e)))))

(defn send-change-ssid
  [container new-ssid]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :change-ssid)
        (action-payload/action-payload container {:new-ssid new-ssid})
        nil)
      (log/warn "Skip change-ssid: no client session bound"))
    (catch Exception e
      (log/error "Error sending change-ssid:"(ex-message e)))))

(defn send-change-password
  [container new-password]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :change-password)
        (action-payload/action-payload container {:new-password new-password})
        nil)
      (log/warn "Skip change-password: no client session bound"))
    (catch Exception e
      (log/error "Error sending change-password:"(ex-message e)))))

(defn rebuild!
  [^UiRt rt container player data]
  (try
    (let [tile (:tile-entity container)
          state (matrix-logic/safe-state tile)
          placer (or (try (.getPlacerName ^IWirelessMatrix tile) (catch Exception _ nil))
                     (matrix-logic/placer-name state)
                     (:owner data "Unknown"))
          is-owner? (matrix-logic/owner-authorized? state player)
          policy (matrix-info-area-policy
                   (boolean (network-initialized? data)) is-owner?)
          ctx (info-area/clear-area! rt)
          load-fn (fn [] (double (:load data)))
          max-capacity (max 1.0 (double (:max-capacity data)))]
      (info-area/add-histogram!
        ctx
        [{:label "Load"
          :color 0xFFFF6C00
          :value-fn load-fn
          :max max-capacity
          :desc-fn (fn [] (str (long (load-fn)) "/" (long max-capacity)))}])
      (info-area/add-sepline! ctx "info")
      (info-area/add-property! ctx "owner" placer)
      (info-area/add-property! ctx "range" (format "%.0f" (double (:range data))))
      (info-area/add-property! ctx "bandwidth" (str (:bandwidth data) " IF/T"))
      (cond
        (network-initialized? data)
        (do
          (info-area/add-sepline! ctx "wireless_info")
          (info-area/add-property! ctx "ssid" (:ssid data)
            :editable? (:editable-ssid? policy)
            :on-change #(send-change-ssid container %))
          (info-area/add-sepline! ctx "change_pass")
          (info-area/add-property! ctx "password" (:password data)
            :editable? (:editable-password? policy)
            :masked? true
            :on-change #(send-change-password container %)))

        (:show-init? policy)
        (let [_ (info-area/add-sepline! ctx "wireless_init")
              ssid-row (info-area/add-property! ctx "ssid" ""
                          :editable? true :color-change? false)
              pass-row (info-area/add-property! ctx "password" ""
                          :editable? true :masked? true :color-change? false)
              ^INode ssid-n (:value-node ssid-row)
              ^INode pass-n (:value-node pass-row)]
          (info-area/add-button! ctx "INIT"
            (fn []
              (let [ssid (str (or (.getOSlot ssid-n 0) ""))
                    pass (str (or (.getOSlot pass-n 0) ""))]
                (log/info "Matrix INIT ssid=" ssid)
                (send-init-network container ssid pass
                  (fn [success]
                    (when success
                      (send-gather-info container
                        (fn [new-data] (rebuild! rt container player new-data))))))))))

        (:show-noinit? policy)
        (info-area/add-sepline! ctx "wireless_noinit")
        :else nil))
    (catch Exception e
      (log/error "matrix-info-reactive rebuild failed:" (ex-message e))
      nil)))

(defn attach!
  [^UiRt rt container player]
  (info-area/ensure-shell! rt)
  (send-gather-info container
    (fn [data] (rebuild! rt container player data))))
