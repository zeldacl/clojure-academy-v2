(ns cn.li.forge1201.integration.forge-energy
  "Forge Energy integration for RF converters (rf-input/rf-output)."
  (:require [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.energy-integration :as energy-integration]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.energy IEnergyCapable]
           [cn.li.forge1201.capability ForgeEnergyAdapter]
           [net.minecraftforge.energy IEnergyStorage]))

(defn- fe-conversion-rate
  []
  (double (energy-integration/forge-energy-conversion-rate)))

(defn- create-forge-energy-adapter
  [^IEnergyCapable energy-capable conversion-rate]
  (ForgeEnergyAdapter. energy-capable (double conversion-rate)))

(defn- get-forge-energy-capability
  [be _side]
  (try
    (when-let [ac-energy-cap (platform-cap/get-capability be :energy-converter nil)]
      (when (platform-cap/is-present? ac-energy-cap)
        (let [ac-energy (platform-cap/or-else ac-energy-cap nil)]
          (when ac-energy
            (create-forge-energy-adapter ac-energy (fe-conversion-rate))))))
    (catch Exception e
      (log/error "Error creating Forge Energy capability:" (ex-message e))
      nil)))

(defn register-forge-energy-capability!
  []
  ;; Declare Forge capability bridge key.
  (platform-cap/declare-capability! :forge-energy IEnergyStorage get-forge-energy-capability)

  ;; Only RF converters should expose Forge Energy externally.
  (tile-logic/register-tile-capability! "rf-input" :forge-energy)
  (tile-logic/register-tile-capability! "rf-output" :forge-energy)

  (log/info "Forge Energy converter bridge enabled for rf-input/rf-output")
  true)

(defn init-forge-energy!
  []
  (log/info "Initializing Forge Energy integration...")
  (register-forge-energy-capability!)
  (log/info "Forge Energy integration initialized"))