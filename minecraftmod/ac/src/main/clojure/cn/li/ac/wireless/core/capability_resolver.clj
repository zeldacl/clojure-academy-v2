(ns cn.li.ac.wireless.core.capability-resolver
  "Resolve wireless capabilities from tiles or VBlocks."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless
            IWirelessGenerator
            IWirelessMatrix
            IWirelessNode
            IWirelessReceiver
            WirelessCapabilityKeys]))

(defn tile-capability
  "Resolve a capability from a tile via the unified tile-logic system.
  All capabilities must be registered via tile-logic/register-tile-capability!;
  there is no instance? fallback — a nil result means the capability is NOT present."
  [tile cap-key _fallback-class]
  (when tile
    (when-let [tile-id (try (platform-be/get-block-id tile)
                            (catch Exception e
                              (log/error "[wireless] tile-capability: get-block-id threw for" cap-key
                                         ":" (ex-message e))
                              nil))]
      (try (tile-logic/get-capability tile-id cap-key tile nil)
           (catch Exception e
             (log/error "[wireless] tile-capability: tile-logic threw for" cap-key
                        "on" tile-id ":" (ex-message e))
             nil)))))

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
