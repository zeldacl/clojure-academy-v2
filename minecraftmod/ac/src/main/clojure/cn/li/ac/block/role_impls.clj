(ns cn.li.ac.block.role-impls
  "Clojure deftype implementations of mcmod Java interfaces.

  Each deftype wraps a ScriptedBlockEntity and delegates to its customState map.
  The customState map is the single source of truth for tile data (Design-3).

  WirelessGeneratorImpl → implements IWirelessGenerator

  Note: WirelessMatrixImpl, WirelessNodeImpl, and ClojureEnergyImpl are defined
  in their respective block files (wireless_matrix/block.clj, wireless_node/block.clj)
  to avoid duplication."
  (:require [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

;; ============================================================================
;; WirelessGeneratorImpl
;; Implements IWirelessGenerator for ScriptedBlockEntity whose customState stores :energy.
;; ============================================================================

(deftype WirelessGeneratorImpl [be]
  IWirelessGenerator

  (getProvidedEnergy [_ req]
    (let [state (or (platform-be/get-custom-state be) {})
          cur (double (get state :energy 0.0))
          bw (double (get state :gen-speed 0.0))
          max-out (if (pos? bw) bw req)
          actual (min (double req) cur max-out)]
      (when (pos? actual)
        (platform-be/set-custom-state! be (assoc state :energy (- cur actual))))
      (double actual)))

  (getGeneratorBandwidth [_]
    (let [state (or (platform-be/get-custom-state be) {})]
      (double (max 0.0 (double (get state :gen-speed 0.0))))))

  Object
  (toString [_]
    (str "WirelessGeneratorImpl@" (pos/position-get-block-pos be))))
