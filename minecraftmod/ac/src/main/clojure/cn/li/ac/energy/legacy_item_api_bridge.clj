(ns cn.li.ac.energy.legacy-item-api-bridge
  "Bridge installer for Java ItemEnergyApi.

  Delegates manager operations to current AC energy operations so internal
  item implementations are recognized by Java-facing APIs."
  (:require [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.energy ItemEnergyApi ItemEnergyApi$Bridge]
           [cn.li.acapi.energy.handle EnergyItemHandle]))

(defn install-item-energy-api-bridge!
  []
  (ItemEnergyApi/installBridge
    (reify ItemEnergyApi$Bridge
      (isSupported [_ item]
        (boolean (energy/is-energy-item-supported? (.rawStack ^EnergyItemHandle item))))

      (getEnergy [_ item]
        (double (or (energy/get-item-energy (.rawStack ^EnergyItemHandle item)) 0.0)))

      (getMaxEnergy [_ item]
        (double (or (energy/get-item-max-energy (.rawStack ^EnergyItemHandle item)) 0.0)))

      (setEnergy [_ item amt]
        (energy/set-item-energy! (.rawStack ^EnergyItemHandle item) amt))

      (charge [_ item amt ignore-bandwidth]
        (double (energy/charge-energy-to-item (.rawStack ^EnergyItemHandle item) amt ignore-bandwidth)))

      (pull [_ item amt ignore-bandwidth]
        (double (energy/pull-energy-from-item (.rawStack ^EnergyItemHandle item) amt ignore-bandwidth)))

      (getDescription [_ item]
        (format "%.0f/%.0f IF"
                (double (or (energy/get-item-energy (.rawStack ^EnergyItemHandle item)) 0.0))
                (double (or (energy/get-item-max-energy (.rawStack ^EnergyItemHandle item)) 0.0))))))
  (log/info "Installed ItemEnergyApi bridge"))

(defn install-if-item-manager-bridge!
  "Deprecated alias. Kept for transitional call sites inside AC init."
  []
  (install-item-energy-api-bridge!))
