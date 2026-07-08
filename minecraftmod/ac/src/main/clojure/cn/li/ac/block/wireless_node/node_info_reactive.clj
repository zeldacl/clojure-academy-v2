(ns cn.li.ac.block.wireless-node.node-info-reactive
  "Reactive info-area for Wireless Node — editable node name + password."
  (:require [cn.li.ac.block.wireless-node.gui :as node-gui]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.gui.info-area-reactive :as info-area]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode]
           [cn.li.mcmod.uipojo.runtime UiRt]))

(def ^:private gui-type :wireless-node)
(defn- msg [action] (msg-registry/msg gui-type action))

(defn rebuild!
  [^UiRt rt container player]
  (try
    (let [tile (:tile-entity container)
          owner-name (node-gui/get-owner container)
          is-owner? (node-logic/owner-authorized? owner-name player)
          policy (node-gui/node-info-area-policy is-owner?)
          node-range (fn []
                       (try (str (.getRange ^IWirelessNode tile))
                            (catch Exception _ "0.0")))
          ctx (info-area/clear-area! rt)]
      (info-area/add-histogram-energy! ctx
        #(double (or @(:energy container) 0.0))
        #(max 1.0 (double (or @(:max-energy container) 1.0))))
      (info-area/add-histogram-capacity! ctx
        #(double (or @(:capacity container) 0.0))
        #(max 1.0 (double (or @(:max-capacity container) 1.0))))
      (info-area/add-sepline! ctx "Info")
      (info-area/add-property! ctx "Range" node-range)
      (info-area/add-property! ctx "Owner" owner-name)
      (if (:editable-node-name? policy)
        (do
          (info-area/add-property! ctx "Node Name" (or @(:ssid container) "")
            :editable? true
            :on-change (fn [new-name]
                         (net-client/send-to-server
                           (msg :change-name)
                           (action-payload/action-payload container {:node-name new-name}))))
          (info-area/add-property! ctx "Password" (or @(:password container) "")
            :editable? (:editable-password? policy)
            :masked? true
            :on-change (fn [new-pass]
                         (net-client/send-to-server
                           (msg :change-password)
                           (action-payload/action-payload container {:password new-pass})))))
        (info-area/add-property! ctx "Node Name" (or @(:ssid container) "")))
      nil)
    (catch Exception e
      (log/error "node-info-reactive rebuild failed:" (ex-message e))
      nil)))

(defn attach!
  [^UiRt rt container player]
  (info-area/ensure-shell! rt)
  (rebuild! rt container player))
