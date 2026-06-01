(ns cn.li.ac.terminal.apps.freq-transmitter-handlers
  "Server-side handlers for Frequency Transmitter commands."
  (:require [clojure.string :as str]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]))

(defn- ->block-pos
  [{:keys [x y z]}]
  (when (and (int? x) (int? y) (int? z))
    (pos/create-block-pos x y z)))

(defn- tile-at
  [level pos-map]
  (when-let [p (->block-pos pos-map)]
    (world/world-get-tile-entity* level p)))

(defn- resolve-matrix-tile
  [tile]
  (if-let [resolve-controller (requiring-resolve 'cn.li.ac.block.wireless-matrix.logic/resolve-controller-be)]
    (or (resolve-controller tile) tile)
    tile))

(defn query-ssid
  [level matrix-pos]
  (when-let [tile (some-> (tile-at level matrix-pos) resolve-matrix-tile)]
    (when-let [cap (resolver/matrix-capability tile)]
      (let [ssid (.getSsid ^cn.li.acapi.wireless.IWirelessMatrix cap)]
        (when-not (str/blank? (str ssid))
          (str ssid))))))

(defn auth-matrix
  [level matrix-pos password]
  (boolean
    (when-let [tile (some-> (tile-at level matrix-pos) resolve-matrix-tile)]
      (when-let [cap (resolver/matrix-capability tile)]
        (= (str password) (str (.getPassword ^cn.li.acapi.wireless.IWirelessMatrix cap)))))))

(defn auth-node
  [level node-pos password]
  (boolean
    (when-let [tile (tile-at level node-pos)]
      (when-let [cap (resolver/node-capability tile)]
        (= (str password) (str (.getPassword ^cn.li.acapi.wireless.IWirelessNode cap)))))))

(defn link-node
  [level node-pos matrix-pos password]
  (let [node-tile (tile-at level node-pos)
        matrix-tile (some-> (tile-at level matrix-pos) resolve-matrix-tile)]
    (boolean
      (when (and node-tile matrix-tile)
        (wireless-api/link-node-to-network! node-tile matrix-tile password)))))

(defn link-user
  [level user-pos node-pos]
  (let [user-tile (tile-at level user-pos)
        node-tile (tile-at level node-pos)]
    (boolean
      (when (and user-tile node-tile)
        (cond
          (resolver/generator-capability user-tile)
          (wireless-api/link-generator-to-node! user-tile node-tile "" false)

          (resolver/receiver-capability user-tile)
          (wireless-api/link-receiver-to-node! user-tile node-tile "" false)

          :else
          false)))))

