(ns cn.li.ac.wireless.core.capability-resolver
  "Resolve wireless capabilities from tiles or VBlocks."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.core.capability-lookup :as cap-lookup]
            [cn.li.mcmod.platform.be :as platform-be])
  (:import [cn.li.acapi.wireless
            IWirelessGenerator
            IWirelessMatrix
            IWirelessNode
            IWirelessReceiver
            WirelessCapabilityKeys]))

(def tile-capability cap-lookup/tile-capability)

(defn matrix-capability [tile]
  (tile-capability tile WirelessCapabilityKeys/MATRIX IWirelessMatrix))

(defn node-capability [tile]
  (tile-capability tile WirelessCapabilityKeys/NODE IWirelessNode))

(defn generator-capability [tile]
  (tile-capability tile WirelessCapabilityKeys/GENERATOR IWirelessGenerator))

(defn receiver-capability [tile]
  (tile-capability tile WirelessCapabilityKeys/RECEIVER IWirelessReceiver))

(defn resolve-tile
  "Resolve a VBlock to the platform tile after chunk/type checks."
  [world vblock]
  (vb/vblock-get vblock world))

(defn resolve-matrix-cap [world matrix-vblock]
  (matrix-capability (resolve-tile world matrix-vblock)))

(defn resolve-node-cap [world node-vblock]
  (node-capability (resolve-tile world node-vblock)))

(defn resolve-generator-cap [world generator-vblock]
  (generator-capability (resolve-tile world generator-vblock)))

(defn resolve-receiver-cap [world receiver-vblock]
  (receiver-capability (resolve-tile world receiver-vblock)))

(defn resolve-capability
  "Resolve the capability implied by a VBlock's block type."
  [world vblock]
  (case (:block-type vblock)
    :matrix (resolve-matrix-cap world vblock)
    :node (resolve-node-cap world vblock)
    :node-conn (resolve-node-cap world vblock)
    :generator (resolve-generator-cap world vblock)
    :receiver (resolve-receiver-cap world vblock)
    nil))
