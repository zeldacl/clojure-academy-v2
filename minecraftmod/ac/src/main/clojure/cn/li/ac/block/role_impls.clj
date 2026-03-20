(ns cn.li.ac.block.role-impls
  "Clojure deftype implementations of mcmod Java interfaces.

  Each deftype wraps a ScriptedBlockEntity and delegates to its customState map.
  The customState map is the single source of truth for tile data (Design-3).

  WirelessMatrixImpl   → implements IWirelessMatrix
  WirelessNodeImpl     → implements IWirelessNode
  ClojureEnergyImpl    → implements IEnergyCapable (platform-neutral energy)"
  (:require [cn.li.ac.block.matrix-schema :as mschema]
            [cn.li.ac.block.node-schema  :as nschema]
            [cn.li.mcmod.block.state-schema :as schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos])
  (:import [my_mod.api.wireless IWirelessMatrix IWirelessNode]
           [my_mod.api.wireless IWirelessGenerator]
           [my_mod.api.energy IEnergyCapable]))

;; ============================================================================
;; WirelessMatrixImpl
;; ============================================================================

(deftype WirelessMatrixImpl [be]
  IWirelessMatrix

  (getMatrixCapacity [_]
    (let [state   (or (platform-be/get-custom-state be) mschema/matrix-default-state)
          plates  (schema/get-field mschema/matrix-state-schema state :plate-count)
          core-lv (schema/get-field mschema/matrix-state-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (int (* 8 core-lv))
        0)))

  (getMatrixBandwidth [_]
    (let [state   (or (platform-be/get-custom-state be) mschema/matrix-default-state)
          plates  (schema/get-field mschema/matrix-state-schema state :plate-count)
          core-lv (schema/get-field mschema/matrix-state-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (double (* core-lv core-lv 60))
        0.0)))

  (getMatrixRange [_]
    (let [state   (or (platform-be/get-custom-state be) mschema/matrix-default-state)
          plates  (schema/get-field mschema/matrix-state-schema state :plate-count)
          core-lv (schema/get-field mschema/matrix-state-schema state :core-level)]
      (if (and (> core-lv 0) (= plates 3))
        (double (* 24 (Math/sqrt core-lv)))
        0.0)))

  (getSsid [_]
    (let [state (or (platform-be/get-custom-state be) mschema/matrix-default-state)]
      (str (schema/get-field mschema/matrix-state-schema state :ssid))))

  (getPassword [_]
    (let [state (or (platform-be/get-custom-state be) mschema/matrix-default-state)]
      (str (schema/get-field mschema/matrix-state-schema state :password))))

  (getPlacerName [_]
    (let [state (or (platform-be/get-custom-state be) mschema/matrix-default-state)]
      (str (schema/get-field mschema/matrix-state-schema state :placer-name))))

  Object
  (toString [_]
    (str "WirelessMatrixImpl@" (pos/position-get-block-pos be))))

;; ============================================================================
;; WirelessNodeImpl
;; ============================================================================

(deftype WirelessNodeImpl [be]
  IWirelessNode

  (getEnergy [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (double (schema/get-field nschema/node-state-schema state :energy))))

  (getMaxEnergy [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (double (nschema/node-max-energy state))))

  (getBandwidth [_]
    (let [state     (or (platform-be/get-custom-state be) nschema/node-default-state)
          node-type (keyword (schema/get-field nschema/node-state-schema state :node-type))]
      (double (get-in nschema/node-types [node-type :bandwidth] 150))))

  (getCapacity [_]
    (let [state     (or (platform-be/get-custom-state be) nschema/node-default-state)
          node-type (keyword (schema/get-field nschema/node-state-schema state :node-type))]
      (int (get-in nschema/node-types [node-type :capacity] 5))))

  (getRange [_]
    (let [state     (or (platform-be/get-custom-state be) nschema/node-default-state)
          node-type (keyword (schema/get-field nschema/node-state-schema state :node-type))]
      (double (get-in nschema/node-types [node-type :range] 9))))

  (getNodeName [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (str (schema/get-field nschema/node-state-schema state :node-name))))

  (getPassword [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (str (schema/get-field nschema/node-state-schema state :password))))

  (getBlockPos [_]
    (pos/position-get-block-pos be))

  Object
  (toString [_]
    (str "WirelessNodeImpl@" (pos/position-get-block-pos be))))

;; ============================================================================
;; ClojureEnergyImpl
;; Implements platform-neutral IEnergyCapable; Forge wraps with IEnergyStorage adapter.
;; ============================================================================

(deftype ClojureEnergyImpl [be]
  IEnergyCapable

  (receiveEnergy [_ max-receive simulate]
    (let [state    (or (platform-be/get-custom-state be) nschema/node-default-state)
          cur      (double (schema/get-field nschema/node-state-schema state :energy))
          max-e    (double (nschema/node-max-energy state))
          can-recv (- max-e cur)
          actual   (min can-recv (double max-receive))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (+ cur actual))))
      (int actual)))

  (extractEnergy [_ max-extract simulate]
    (let [state  (or (platform-be/get-custom-state be) nschema/node-default-state)
          cur    (double (schema/get-field nschema/node-state-schema state :energy))
          actual (min cur (double max-extract))]
      (when (and (not simulate) (pos? actual))
        (platform-be/set-custom-state! be (assoc state :energy (- cur actual))))
      (int actual)))

  (getEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (int (schema/get-field nschema/node-state-schema state :energy))))

  (getMaxEnergyStored [_]
    (let [state (or (platform-be/get-custom-state be) nschema/node-default-state)]
      (int (nschema/node-max-energy state))))

  (canExtract [_] true)
  (canReceive [_] true)

  Object
  (toString [_]
    (str "ClojureEnergyImpl@" (pos/position-get-block-pos be))))

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
