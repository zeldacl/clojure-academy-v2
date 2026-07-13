(ns cn.li.ac.block.wireless-node.node-info-reactive
  "Reactive info-area for Wireless Node — editable node name + password."
  (:require [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.gui.info-area-reactive :as info-area]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode]
           [cn.li.mcmod.uipojo.runtime UiRt]))

(def ^:private gui-type :wireless-node)
(defn- msg [action] (msg-registry/msg gui-type action))

(defn- get-owner
  "Ported verbatim from the deleted wireless_node/gui.clj."
  [container]
  (let [tile (:tile-entity container)]
    (node-logic/owner-name (common/get-tile-state tile))))

(defn- node-info-area-policy
  "Compute editable policy for node info-area fields (ported verbatim from
   the deleted wireless_node/gui.clj)."
  [is-owner?]
  {:editable-node-name? (boolean is-owner?)
   :editable-password? (boolean is-owner?)})

(defn- send-owner
  "Resolve a client owner for GUI-initiated server requests (same pattern as
  matrix_info_reactive). Falls back to nil when no client session is bound."
  []
  (runtime-hooks/default-client-owner))

(defn rebuild!
  [^UiRt rt container player]
  (try
    (let [tile (:tile-entity container)
          owner-name (get-owner container)
          is-owner? (node-logic/owner-authorized? owner-name player)
          policy (node-info-area-policy is-owner?)
          node-range (fn []
                       (try (str (.getRange ^IWirelessNode tile))
                            (catch Exception _ "0.0")))
          ctx (info-area/clear-area! rt)]
      (info-area/add-histogram! ctx
        [{:label "Energy" :color 0xFF25C4FF
          :value-fn (fn [] (double (or @(:energy container) 0.0)))
          :max (max 1.0 (double (or @(:max-energy container) 1.0)))
          :desc-fn (fn [] (format "%.0f IF" (double (or @(:energy container) 0.0))))}
         {:label "Load" :color 0xFFFF6C00
          :value-fn (fn [] (double (or @(:capacity container) 0.0)))
          :max (max 1.0 (double (or @(:max-capacity container) 1.0)))
          :desc-fn (fn [] (str (long (or @(:capacity container) 0))
                               "/" (long (max 1.0 (double (or @(:max-capacity container) 1.0))))))}])
      (info-area/add-sepline! ctx "Info")
      (info-area/add-property! ctx "Range" node-range)
      (info-area/add-property! ctx "Owner" owner-name)
      (if (:editable-node-name? policy)
        (do
          (info-area/add-property! ctx "Node Name" (or @(:ssid container) "")
            :editable? true
            :on-change (fn [new-name]
                         (if-let [owner (send-owner)]
                           (net-client/send-to-server
                             owner
                             (msg :change-name)
                             (action-payload/action-payload container {:node-name new-name}))
                           (log/warn "Skip change-name: no client session bound"))))
          (info-area/add-property! ctx "Password" (or @(:password container) "")
            :editable? (:editable-password? policy)
            :masked? true
            :on-change (fn [new-pass]
                         (if-let [owner (send-owner)]
                           (net-client/send-to-server
                             owner
                             (msg :change-password)
                             (action-payload/action-payload container {:password new-pass}))
                           (log/warn "Skip change-password: no client session bound")))))
        (info-area/add-property! ctx "Node Name" (or @(:ssid container) "")))
      nil)
    (catch Exception e
      (log/error "node-info-reactive rebuild failed:" (ex-message e))
      (log/stacktrace "node-info-reactive rebuild exception:" e)
      nil)))

(defn attach!
  [^UiRt rt container player]
  (info-area/ensure-shell! rt)
  (rebuild! rt container player))
