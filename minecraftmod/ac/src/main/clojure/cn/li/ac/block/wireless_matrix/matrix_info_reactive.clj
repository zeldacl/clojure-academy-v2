(ns cn.li.ac.block.wireless-matrix.matrix-info-reactive
  "Typed network actions used by the Wireless Matrix ViewModel."
  (:require [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.util.log :as log]))

(defrecord MatrixNetworkData
  [ssid password owner load max-capacity range bandwidth initialized])

(defn network-initialized? [data] (:initialized data))

(defn matrix-info-area-policy [initialized? is-owner?]
  (let [owner? (true? is-owner?)]
    (cond
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

(defn attach! [& _] nil)
(defn rebuild! [& _] nil)
