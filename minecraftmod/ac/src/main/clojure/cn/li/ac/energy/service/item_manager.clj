(ns cn.li.ac.energy.service.item-manager
  "Item energy management service.

  Wraps legacy battery helpers behind the new Phase C energy service layer,
  while keeping behavior compatible with existing callers."
  (:require [cn.li.ac.energy.domain.container :as container]
            [cn.li.ac.item.test-battery :as battery]))

(defn is-energy-item-supported?
  "Return true when the item stack supports AC energy storage."
  [item-stack]
  (boolean (and item-stack (battery/is-battery? item-stack))))

(defn get-item-energy
  "Read current energy from an item stack."
  [item-stack]
  (double (or (when (is-energy-item-supported? item-stack)
                (battery/get-battery-energy item-stack))
              0.0)))

(defn get-item-capacity
  "Read maximum energy capacity from an item stack."
  [item-stack]
  (double (or (when (is-energy-item-supported? item-stack)
                (battery/get-max-battery-energy item-stack))
              0.0)))

(defn get-item-bandwidth
  "Read item transfer bandwidth."
  [item-stack]
  (double (or (when (is-energy-item-supported? item-stack)
                (battery/get-battery-bandwidth item-stack))
              0.0)))

(defn set-item-energy!
  "Set energy on an item stack in place.

  Returns the updated energy value for convenience."
  [item-stack amount]
  (if (is-energy-item-supported? item-stack)
    (do
      (battery/set-battery-energy! item-stack amount)
      (get-item-energy item-stack))
    0.0))

(defn charge-energy-to-item
  "Charge an item and return leftover energy that could not be inserted."
  [item-stack amount ignore-bandwidth]
  (if (is-energy-item-supported? item-stack)
    (double (battery/charge-battery! item-stack amount ignore-bandwidth))
    (double amount)))

(defn pull-energy-from-item
  "Pull energy from an item and return the amount actually extracted."
  [item-stack amount ignore-bandwidth]
  (if (is-energy-item-supported? item-stack)
    (double (battery/pull-from-battery! item-stack amount ignore-bandwidth))
    0.0))

(defn item->container
  "Project an item stack into the immutable EnergyContainer model."
  [item-stack]
  (when (is-energy-item-supported? item-stack)
    (container/energy-container
      (max 1.0 (get-item-capacity item-stack))
      (get-item-energy item-stack)
      (max 1.0 (get-item-bandwidth item-stack))
      1.0)))

(defn charge-item
  "Protocol-friendly alias returning amount successfully charged."
  [item-stack amount]
  (let [leftover (charge-energy-to-item item-stack amount false)]
    (- (double amount) leftover)))

(defn discharge-item
  "Protocol-friendly alias returning amount successfully discharged."
  [item-stack amount]
  (pull-energy-from-item item-stack amount false))
