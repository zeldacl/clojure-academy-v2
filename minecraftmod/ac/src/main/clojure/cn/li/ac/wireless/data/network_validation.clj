(ns cn.li.ac.wireless.data.network-validation
  "Validation and disposal operations for wireless networks."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.core.capability-resolver :as resolver]
            [cn.li.ac.wireless.data.network-state :as net-state]
            [cn.li.mcmod.util.log :as log]))

(defn validate!
  "Validate network integrity.
  Returns true if valid, false if should be disposed."
  [network world]
  (let [matrix-vb (:matrix network)
        chunk-loaded? (vb/is-chunk-loaded? matrix-vb world)
        cap (resolver/resolve-matrix-cap world matrix-vb)]
    (if (and chunk-loaded? (not cap))
      (do
        (log/info (str "[validate] Network '" (net-state/get-ssid network)
                       "' chunk-loaded?=" chunk-loaded?
                       " cap=" (pr-str cap)
                       " matrix-vb=" (pr-str (select-keys matrix-vb [:x :y :z :block-type :ignore-chunk]))
                       " => disposing (matrix destroyed)"))
        (net-state/mark-disposed! network))
      network)
    (net-state/active? network)))

(defn is-in-range?
  "Check if coordinates are in network range."
  [network x y z]
  (if-let [matrix (net-state/get-matrix network)]
    (let [range (.getMatrixRange ^cn.li.acapi.wireless.IWirelessMatrix matrix)
          dist-sq (vb/dist-sq-pos (:matrix network) x y z)]
      (<= dist-sq (* range range)))
    false))

(defn dispose!
  "Dispose the network and unlink all nodes."
  [network world]
  (net-state/mark-disposed! network)
  (log/info (format "Network '%s' disposed" (net-state/get-ssid network))))