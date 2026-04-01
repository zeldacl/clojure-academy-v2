(ns cn.li.ac.wireless.legacy-api-bridge
  "Bridge installer for Java WirelessQueryApi.

  Keeps external method names stable while delegating to the current
  cn.li.ac.wireless.api implementation."
  (:require [cn.li.ac.wireless.api :as wapi]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.energy WirelessQueryApi WirelessQueryApi$Bridge]
           [cn.li.acapi.energy.handle BlockPoint NodeConnectionRef WirelessNetworkRef WorldHandle]
           [cn.li.acapi.wireless IWirelessGenerator IWirelessNode IWirelessReceiver]))

(defn install-wireless-query-api-bridge!
  []
  (WirelessQueryApi/installBridge
    (reify WirelessQueryApi$Bridge
      (getWirelessNetByMatrix [_ matrix]
        (when-let [net (wapi/get-wireless-net-by-matrix matrix)]
          (WirelessNetworkRef/of net)))

      (getWirelessNetByNode [_ node]
        (when-let [net (wapi/get-wireless-net-by-node node)]
          (WirelessNetworkRef/of net)))

      (getNetInRange [_ world center range max-results]
        (mapv (fn [net] (WirelessNetworkRef/of net))
              (wapi/get-nets-in-range (.rawWorld ^WorldHandle world)
                                      (.x ^BlockPoint center)
                                      (.y ^BlockPoint center)
                                      (.z ^BlockPoint center)
                                      range
                                      max-results)))

      (getNodeConnByNode [_ node]
        (when-let [conn (wapi/get-node-conn-by-node node)]
          (NodeConnectionRef/of conn)))

      (getNodeConnByUser [_ user]
        (cond
          (instance? IWirelessGenerator user)
          (when-let [conn (wapi/get-node-conn-by-generator user)]
            (NodeConnectionRef/of conn))

          (instance? IWirelessReceiver user)
          (when-let [conn (wapi/get-node-conn-by-receiver user)]
            (NodeConnectionRef/of conn))

          (instance? IWirelessNode user)
          (when-let [conn (wapi/get-node-conn-by-node user)]
            (NodeConnectionRef/of conn))

          :else nil))

      (getNodesInRange [_ world center]
        (wapi/get-nodes-in-range-at (.rawWorld ^WorldHandle world)
                  (.x ^BlockPoint center)
                  (.y ^BlockPoint center)
                  (.z ^BlockPoint center)))))
  (log/info "Installed WirelessQueryApi bridge"))

(defn install-wireless-helper-bridge!
  "Deprecated alias. Kept for transitional call sites inside AC init."
  []
  (install-wireless-query-api-bridge!))
