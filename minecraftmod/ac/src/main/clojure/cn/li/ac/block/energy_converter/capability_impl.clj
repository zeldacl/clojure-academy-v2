(ns cn.li.ac.block.energy-converter.capability-impl
  "Energy Converter capability implementation - IEnergyCapable protocol"
  (:require [cn.li.ac.block.energy-converter.config :as converter-config]
            [cn.li.ac.block.energy-converter.face-config :as face-config]
            [cn.li.ac.energy.operations :as energy-ops])
  (:import [cn.li.acapi.energy IEnergyCapable]))

(defn create-energy-capable
  "Create IEnergyCapable implementation for energy converter tile entity.

  Args:
    tile-entity - The ScriptedBlockEntity instance
    get-state-fn - Function to get current state map
    set-state-fn - Function to update state map

  Returns:
    IEnergyCapable implementation"
  [tile-entity get-state-fn set-state-fn]
  (reify IEnergyCapable
    (receiveEnergy [_ max-receive simulate]
      ;; Receive energy from external sources (Forge Energy, etc.)
      ;; Check face configuration if side is provided
      (let [state (get-state-fn)
            current-energy (double (get state :energy 0.0))
            max-energy (converter-config/max-energy)
            space (- max-energy current-energy)
            to-receive (min max-receive space)
            to-receive-int (int to-receive)]
        (when-not simulate
          (set-state-fn (assoc state :energy (+ current-energy to-receive))))
        to-receive-int))

    (extractEnergy [_ max-extract simulate]
      ;; Extract energy to external consumers
      ;; Check face configuration if side is provided
      (let [state (get-state-fn)
            current-energy (double (get state :energy 0.0))
            to-extract (min max-extract current-energy)
            to-extract-int (int to-extract)]
        (when-not simulate
          (set-state-fn (assoc state :energy (- current-energy to-extract))))
        to-extract-int))

    (getEnergyStored [_]
      (let [state (get-state-fn)
            energy (double (get state :energy 0.0))]
        (int energy)))

    (getMaxEnergyStored [_]
      (int (converter-config/max-energy)))

    (canExtract [_]
      ;; Can extract if mode is :export-fe or :charge-items
      (let [state (get-state-fn)
            mode (get state :mode "charge-items")]
        (or (= mode "export-fe")
            (= mode "charge-items"))))

    (canReceive [_]
      ;; Can receive if mode is :import-fe or :charge-items
      (let [state (get-state-fn)
            mode (get state :mode "charge-items")]
        (or (= mode "import-fe")
            (= mode "charge-items"))))))

(defn charge-items-in-slots
  "Charge energy items in converter slots.

  Args:
    state - Current tile entity state
    max-transfer - Maximum IF to transfer per operation

  Returns:
    Updated state with modified energy and items"
  [state max-transfer]
  (let [current-energy (double (get state :energy 0.0))
        input-slot (get state :input-slot)
        output-slot (get state :output-slot)]

    (if (and input-slot (energy-ops/is-energy-item-supported? input-slot))
      (let [;; Try to charge the input item
            item-max-energy (energy-ops/get-item-max-energy input-slot)
            item-current-energy (energy-ops/get-item-energy input-slot)
            item-space (- item-max-energy item-current-energy)
            to-transfer (min max-transfer current-energy item-space)
            leftover (energy-ops/charge-energy-to-item input-slot to-transfer false)
            actually-transferred (- to-transfer leftover)
            new-energy (- current-energy actually-transferred)]

        ;; Update state with new energy
        (assoc state
               :energy new-energy
               :transfer-rate actually-transferred))

      ;; No chargeable item, reset transfer rate
      (assoc state :transfer-rate 0.0))))
