(ns cn.li.ac.wireless.core.vblock-resolver
  "Runtime resolver for wireless virtual block references.

  This namespace owns world/chunk/tile/capability lookup. It deliberately stays
  separate from NBT codecs so persistence remains data-only."
  (:require [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world])
  (:import [cn.li.acapi.wireless
            IWirelessGenerator
            IWirelessMatrix
            IWirelessNode
            IWirelessReceiver
            WirelessCapabilityKeys]))

(defn vblock-pos
  "Get BlockPos for a vblock using the platform abstraction."
  [vblock]
  (pos/create-block-pos (:x vblock) (:y vblock) (:z vblock)))

(defn is-chunk-loaded?
  "Check if the chunk containing this vblock is loaded."
  [vblock w]
  (let [chunk-x (bit-shift-right (:x vblock) 4)
        chunk-z (bit-shift-right (:z vblock) 4)]
    (world/world-is-chunk-loaded?* w chunk-x chunk-z)))

(defn- has-capability?
  "Return true if tile exposes the named wireless capability."
  [tile cap-key fallback-class]
  (when tile
    (try
      (or (when-let [tile-id (platform-be/get-block-id tile)]
            (some? (tile-logic/get-capability tile-id cap-key tile nil)))
          (instance? fallback-class tile))
      (catch Exception _ false))))

(defn- tile-has-wireless-matrix? [tile]
  (if (map? tile)
    (contains? tile :plate-count)
    (or (has-capability? tile WirelessCapabilityKeys/MATRIX IWirelessMatrix)
        (instance? IWirelessMatrix tile))))

(defn- tile-has-wireless-node? [tile]
  (if (map? tile)
    (contains? tile :node-type)
    (or (has-capability? tile WirelessCapabilityKeys/NODE IWirelessNode)
        (instance? IWirelessNode tile))))

(defn- tile-has-wireless-generator? [tile]
  (or (has-capability? tile WirelessCapabilityKeys/GENERATOR IWirelessGenerator)
      (instance? IWirelessGenerator tile)))

(defn- tile-has-wireless-receiver? [tile]
  (or (has-capability? tile WirelessCapabilityKeys/RECEIVER IWirelessReceiver)
      (instance? IWirelessReceiver tile)))

(defn vblock-get
  "Get the TileEntity/state for this vblock with chunk and capability checks."
  [vblock w]
  (when w
    (when (or (:ignore-chunk vblock) (is-chunk-loaded? vblock w))
      (let [block-pos (vblock-pos vblock)
            tile      (world/world-get-tile-entity* w block-pos)]
        (when tile
          (case (:block-type vblock)
            :matrix    (when (tile-has-wireless-matrix?    tile) tile)
            :node      (when (tile-has-wireless-node?      tile) tile)
            :node-conn (when (tile-has-wireless-node?      tile) tile)
            :generator (when (tile-has-wireless-generator? tile) tile)
            :receiver  (when (tile-has-wireless-receiver?  tile) tile)
            nil))))))
