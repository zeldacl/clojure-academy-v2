(ns cn.li.ac.energy.service.node-manager
  "Wireless node and receiver energy management service.

  Provides a focused service layer over the existing
  IWirelessNode / IWirelessReceiver contracts."
  (:require [cn.li.ac.energy.domain.container :as container])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessReceiver]))

(defn is-node-supported?
  "Return true when the tile entity supports the wireless node API."
  [tile-entity]
  (instance? IWirelessNode tile-entity))

(defn is-receiver-supported?
  "Return true when the tile entity supports the wireless receiver API."
  [tile-entity]
  (instance? IWirelessReceiver tile-entity))

(defn get-node-energy
  "Read node energy, or nil when unsupported."
  [tile-entity]
  (when (is-node-supported? tile-entity)
    (double (.getEnergy ^IWirelessNode tile-entity))))

(defn get-node-capacity
  "Read node max energy, or nil when unsupported."
  [tile-entity]
  (when (is-node-supported? tile-entity)
    (double (.getMaxEnergy ^IWirelessNode tile-entity))))

(defn get-node-bandwidth
  "Read node transfer bandwidth, or nil when unsupported."
  [tile-entity]
  (when (is-node-supported? tile-entity)
    (double (.getBandwidth ^IWirelessNode tile-entity))))

(defn set-node-energy!
  "Set node energy in place and return the applied value."
  [tile-entity amount]
  (when (is-node-supported? tile-entity)
    (.setEnergy ^IWirelessNode tile-entity (double amount))
    (get-node-energy tile-entity)))

(defn charge-node
  "Insert energy into a node.

  Returns leftover energy that could not be inserted."
  [tile-entity amount ignore-bandwidth]
  (if (is-node-supported? tile-entity)
    (let [node ^IWirelessNode tile-entity
          current (.getEnergy node)
          max-energy (.getMaxEnergy node)
          bandwidth (.getBandwidth node)
          space (- max-energy current)
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-charge (min amount space limit)
          leftover (- amount to-charge)]
      (.setEnergy node (+ current to-charge))
      (double leftover))
    (double amount)))

(defn pull-from-node
  "Extract energy from a node.

  Returns the amount successfully extracted."
  [tile-entity amount ignore-bandwidth]
  (if (is-node-supported? tile-entity)
    (let [node ^IWirelessNode tile-entity
          current (.getEnergy node)
          bandwidth (.getBandwidth node)
          limit (if ignore-bandwidth Double/MAX_VALUE bandwidth)
          to-pull (min amount current limit)]
      (.setEnergy node (- current to-pull))
      (double to-pull))
    0.0))

(defn charge-receiver
  "Inject energy into a receiver and return leftover energy."
  [tile-entity amount]
  (if (is-receiver-supported? tile-entity)
    (double (.injectEnergy ^IWirelessReceiver tile-entity amount))
    (double amount)))

(defn pull-from-receiver
  "Pull energy from a receiver and return the amount extracted."
  [tile-entity amount]
  (if (is-receiver-supported? tile-entity)
    (double (.pullEnergy ^IWirelessReceiver tile-entity amount))
    0.0))

(defn node->container
  "Project a node into the immutable EnergyContainer model."
  [tile-entity]
  (when (is-node-supported? tile-entity)
    (container/energy-container
      (max 1.0 (or (get-node-capacity tile-entity) 1.0))
      (double (or (get-node-energy tile-entity) 0.0))
      (max 1.0 (or (get-node-bandwidth tile-entity) 1.0))
      1.0)))

(defn inject-energy
  "Protocol-friendly alias returning amount successfully inserted."
  [tile-entity amount]
  (let [leftover (charge-node tile-entity amount false)]
    (- (double amount) leftover)))

(defn extract-node-energy
  "Protocol-friendly alias returning amount successfully extracted."
  [tile-entity amount]
  (pull-from-node tile-entity amount false))
