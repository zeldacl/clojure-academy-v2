(ns cn.li.ac.block.wireless-matrix.matrix-info-reactive
  "Reactive info-area for Wireless Matrix — SSID/password editing + INIT form."
  (:require [cn.li.ac.block.wireless-matrix.gui :as matrix-gui]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.gui.info-area-reactive :as info-area]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessMatrix]
           [cn.li.mcmod.uipojo.runtime UiRt]))

(defn rebuild!
  [^UiRt rt container player data]
  (try
    (let [tile (:tile-entity container)
          state (matrix-logic/safe-state tile)
          placer (or (try (.getPlacerName ^IWirelessMatrix tile) (catch Exception _ nil))
                     (matrix-logic/placer-name state)
                     (:owner data "Unknown"))
          is-owner? (matrix-logic/owner-authorized? state player)
          policy (matrix-gui/matrix-info-area-policy
                   (boolean (matrix-gui/network-initialized? data)) is-owner?)
          ctx (info-area/clear-area! rt)]
      (info-area/add-histogram-capacity! ctx #(double (:load data)) (double (:max-capacity data)))
      (info-area/add-sepline! ctx "info")
      (info-area/add-property! ctx "owner" placer)
      (info-area/add-property! ctx "range" (format "%.0f" (double (:range data))))
      (info-area/add-property! ctx "bandwidth" (str (:bandwidth data) " IF/T"))
      (cond
        (matrix-gui/network-initialized? data)
        (do
          (info-area/add-sepline! ctx "wireless_info")
          (info-area/add-property! ctx "ssid" (:ssid data)
            :editable? (:editable-ssid? policy)
            :on-change #(matrix-gui/send-change-ssid container %))
          (info-area/add-sepline! ctx "change_pass")
          (info-area/add-property! ctx "password" (:password data)
            :editable? (:editable-password? policy)
            :masked? true
            :on-change #(matrix-gui/send-change-password container %)))

        (:show-init? policy)
        (let [_ (info-area/add-sepline! ctx "wireless_init")
              ssid-row (info-area/add-property! ctx "ssid" ""
                          :editable? true :color-change? false)
              pass-row (info-area/add-property! ctx "password" ""
                          :editable? true :masked? true :color-change? false)
              ssid-n (:value-node ssid-row)
              pass-n (:value-node pass-row)]
          (info-area/add-button! ctx "INIT"
            (fn []
              (let [ssid (str (or (.getOSlot ssid-n 0) ""))
                    pass (str (or (.getOSlot pass-n 0) ""))]
                (log/info "Matrix INIT ssid=" ssid)
                (matrix-gui/send-init-network container ssid pass
                  (fn [success]
                    (when success
                      (matrix-gui/send-gather-info container
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
  (matrix-gui/send-gather-info container
    (fn [data] (rebuild! rt container player data))))
