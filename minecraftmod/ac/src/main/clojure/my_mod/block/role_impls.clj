(ns my-mod.block.role-impls
  "Clojure deftype implementations of mcmod Java interfaces.

  Each deftype wraps a ScriptedBlockEntity and delegates to its customState map.
  The customState map is the single source of truth for tile data (Design-3).

  WirelessMatrixImpl   → implements IWirelessMatrix
  WirelessNodeImpl     → implements IWirelessNode
  ClojureEnergyImpl    → implements IEnergyCapable (platform-neutral energy)"
  (:import [my_mod.api.wireless IWirelessMatrix IWirelessNode]
           [my_mod.api.energy IEnergyCapable]))

;; ============================================================================
;; WirelessMatrixImpl
;; ============================================================================

(deftype WirelessMatrixImpl [be]
  IWirelessMatrix

  (getMatrixCapacity [_]
    (let [state (.getCustomState be)]
      (if state
        (let [plates  (get state :plate-count 0)
              core-lv (get state :core-level 0)]
          (if (and (> core-lv 0) (= plates 3))
            (int (* 8 core-lv))
            0))
        0)))

  (getMatrixBandwidth [_]
    (let [state (.getCustomState be)]
      (if state
        (let [plates  (get state :plate-count 0)
              core-lv (get state :core-level 0)]
          (if (and (> core-lv 0) (= plates 3))
            (double (* core-lv core-lv 60))
            0.0))
        0.0)))

  (getMatrixRange [_]
    (let [state (.getCustomState be)]
      (if state
        (let [plates  (get state :plate-count 0)
              core-lv (get state :core-level 0)]
          (if (and (> core-lv 0) (= plates 3))
            (double (* 24 (Math/sqrt core-lv)))
            0.0))
        0.0)))

  Object
  (toString [_]
    (str "WirelessMatrixImpl@" (.getBlockPos be))))

;; ============================================================================
;; WirelessNodeImpl
;; ============================================================================

(deftype WirelessNodeImpl [be]
  IWirelessNode

  (getEnergy [_]
    (double (get (.getCustomState be) :energy 0.0)))

  (getMaxEnergy [_]
    (let [state (.getCustomState be)
          node-type (keyword (get state :node-type "basic"))]
      (case node-type
        :advanced 200000.0
        :standard  50000.0
                   15000.0)))

  (getBandwidth [_]
    (let [node-type (keyword (get (.getCustomState be) :node-type "basic"))]
      (case node-type
        :advanced 900.0
        :standard 300.0
                  150.0)))

  (getCapacity [_]
    (let [node-type (keyword (get (.getCustomState be) :node-type "basic"))]
      (case node-type
        :advanced 20
        :standard 10
                   5)))

  (getRange [_]
    (let [node-type (keyword (get (.getCustomState be) :node-type "basic"))]
      (case node-type
        :advanced 19.0
        :standard 12.0
                   9.0)))

  (getNodeName [_]
    (str (get (.getCustomState be) :node-name "Unnamed")))

  (getPassword [_]
    (str (get (.getCustomState be) :password "")))

  Object
  (toString [_]
    (str "WirelessNodeImpl@" (.getBlockPos be))))

;; ============================================================================
;; ClojureEnergyImpl
;; Implements platform-neutral IEnergyCapable; Forge wraps with IEnergyStorage adapter.
;; ============================================================================

(deftype ClojureEnergyImpl [be]
  IEnergyCapable

  (receiveEnergy [_ max-receive simulate]
    (let [state    (.getCustomState be)
          cur      (double (get state :energy 0.0))
          max-e    (double (get state :max-energy 15000.0))
          can-recv (- max-e cur)
          actual   (min can-recv (double max-receive))]
      (when (and (not simulate) (pos? actual))
        (.setCustomState be (assoc state :energy (+ cur actual))))
      (int actual)))

  (extractEnergy [_ max-extract simulate]
    (let [state   (.getCustomState be)
          cur     (double (get state :energy 0.0))
          actual  (min cur (double max-extract))]
      (when (and (not simulate) (pos? actual))
        (.setCustomState be (assoc state :energy (- cur actual))))
      (int actual)))

  (getEnergyStored [_]
    (int (get (.getCustomState be) :energy 0.0)))

  (getMaxEnergyStored [_]
    (int (get (.getCustomState be) :max-energy 15000.0)))

  (canExtract [_] true)
  (canReceive [_] true)

  Object
  (toString [_]
    (str "ClojureEnergyImpl@" (.getBlockPos be))))
