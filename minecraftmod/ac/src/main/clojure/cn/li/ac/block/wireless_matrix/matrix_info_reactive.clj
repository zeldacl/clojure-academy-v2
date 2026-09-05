(ns cn.li.ac.block.wireless-matrix.matrix-info-reactive
  "Typed network actions used by the Wireless Matrix ViewModel."
  (:require [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.info-area :as info-area]))

(defrecord MatrixNetworkData
  [ssid password owner load max-capacity range bandwidth initialized])

(defn network-initialized? [data] (:initialized data))

(defn matrix-info-area-policy [initialized? is-owner?]
  (let [owner? (true? is-owner?)]
    (cond
      (and (some? initialized?) (not (boolean? initialized?))) {:show-init? false :show-noinit? false
                                      :editable-ssid? false :editable-password? false}
      (true? initialized?) {:show-init? false :show-noinit? false
                            :editable-ssid? owner? :editable-password? owner?}
      owner? {:show-init? true :show-noinit? false
              :editable-ssid? false :editable-password? false}
      :else {:show-init? false :show-noinit? true
             :editable-ssid? false :editable-password? false})))

(defn- msg [action] (msg-registry/msg :matrix action))
(defn- current-client-owner [] (runtime-hooks/default-client-owner))

(defn send-gather-info [container callback]
  (when-let [owner (current-client-owner)]
    (net-client/send-to-server owner (msg :gather-info)
      (action-payload/action-payload container {})
      (fn [response]
        (callback (map->MatrixNetworkData
                    {:ssid (:ssid response) :password (:password response)
                     :owner (or (:owner response) "Unknown")
                     :load (or (:load response) 0)
                     :max-capacity (or (:max-capacity response) 16)
                     :range (or (:range response) 64)
                     :bandwidth (or (:bandwidth response) 100)
                     :initialized (boolean (if (contains? response :initialized)
                                              (:initialized response) (:ssid response)))}))))))

(defn send-init-network [container ssid password callback]
  (when-let [owner (current-client-owner)]
    (net-client/send-to-server owner (msg :init)
      (action-payload/action-payload container {:ssid ssid :password password})
      (fn [response] (callback (boolean (:success response)))))))

(defn send-change-ssid [container new-ssid]
  (when-let [owner (current-client-owner)]
    (net-client/send-to-server owner (msg :change-ssid)
      (action-payload/action-payload container {:new-ssid new-ssid}) nil)))

(defn send-change-password [container new-password]
  (when-let [owner (current-client-owner)]
    (net-client/send-to-server owner (msg :change-password)
      (action-payload/action-payload container {:new-password new-password}) nil)))

(defn info-area-snapshot
  "Presentation projection matching main `rebuild!` (owner/range/bandwidth +
   ssid/password or INIT / noinit). Capacity hist bars are applied by
   `info-area/shared-info-hist` from `:presentation-network`."
  [data is-owner?]
  (let [initialized? (boolean (network-initialized? data))
        owner? (boolean is-owner?)
        policy (matrix-info-area-policy initialized? owner?)
        capacity (double (or (:load data) 0.0))
        max-capacity (double (or (:max-capacity data) 0.0))
        load-ratio (info-area/fill-ratio capacity max-capacity)
        ;; Main order after hist + "-- info --": owner, range, bandwidth.
        base-fields [(info-area/field-entry
                       {:id :owner :label "Owner"
                        :value (str (or (:owner data) "Unknown"))})
                     (info-area/field-entry
                       {:id :range :label "Range"
                        :value (format "%.0f" (double (or (:range data) 0)))})
                     (info-area/field-entry
                       {:id :bandwidth :label "Bandwidth"
                        :value (str (or (:bandwidth data) 0) " IF/T")})]
        wifi-fields
        (cond
          initialized?
          [(info-area/field-entry
             {:id :node-name :label "SSID"
              :value (str (or (:ssid data) ""))
              :editable? (boolean (:editable-ssid? policy))
              :draft-key :node-name})
           (info-area/field-entry
             {:id :password :label "Password"
              :value (str (or (:password data) ""))
              :editable? (boolean (:editable-password? policy))
              :masked? true
              :draft-key :network-password})]

          ;; Main init form: editable even though policy editable-* is false.
          ;; Seed from network* (text-change merges drafts there) so a snapshot
          ;; rebuild does not blank rows before merge-drafts re-applies.
          (:show-init? policy)
          [(info-area/field-entry
             {:id :node-name :label "SSID"
              :value (str (or (info-area/draft-value data :node-name) ""))
              :editable? true :draft-key :node-name})
           (info-area/field-entry
             {:id :password :label "Password"
              :value (str (or (info-area/draft-value data :network-password) ""))
              :editable? true :masked? true :draft-key :network-password})]

          :else [])
        fields (into base-fields wifi-fields)]
    {:title "Info"
     :sep-label "-- Info --"
     :sep-visible? true
     :initialized? initialized?
     :editable? (and initialized? owner?)
     :load-ratio load-ratio
     :fields fields
     :init-visible? (boolean (:show-init? policy))
     :init-button {:label "INIT"}
     :noinit-visible? (boolean (:show-noinit? policy))
     :noinit-label "-- Network unavailable --"}))
