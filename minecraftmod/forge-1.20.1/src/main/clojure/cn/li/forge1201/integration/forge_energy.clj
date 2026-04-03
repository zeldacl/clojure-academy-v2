(ns cn.li.forge1201.integration.forge-energy
  "Forge Energy integration - exposes AC energy converters as Forge Energy providers/consumers.

  This module registers Forge Energy capability for energy converter blocks,
  allowing external mods (Mekanism, Thermal, etc.) to interact with AC energy."
  (:require [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.energy-converter.config :as converter-config])
  (:import [cn.li.acapi.energy IEnergyCapable]
           [cn.li.forge1201.capability ForgeEnergyAdapter]
           [net.minecraftforge.common.capabilities Capability ForgeCapabilities]))

(set! *warn-on-reflection* true)

(defn- create-forge-energy-adapter
  "Create a ForgeEnergyAdapter that wraps an IEnergyCapable.

  Args:
    energy-capable - The IEnergyCapable implementation
    conversion-rate - Conversion rate (1 IF = X FE)

  Returns:
    ForgeEnergyAdapter instance"
  [^IEnergyCapable energy-capable conversion-rate]
  (ForgeEnergyAdapter. energy-capable (double conversion-rate)))

(defn- get-forge-energy-capability
  "Get Forge Energy capability for a block entity.

  This is called by the capability system when external mods query for
  ForgeCapabilities.ENERGY on our energy converter blocks.

  Args:
    be - The block entity
    side - The side being queried (or nil for any side)

  Returns:
    ForgeEnergyAdapter instance or nil"
  [be _side]
  (try
    ;; Get the AC energy capability first
    (when-let [ac-energy-cap (platform-cap/get-capability be :energy-converter nil)]
      (when (platform-cap/is-present? ac-energy-cap)
        (let [ac-energy (platform-cap/or-else ac-energy-cap nil)]
          (when ac-energy
            ;; Wrap it in a Forge Energy adapter
            (create-forge-energy-adapter ac-energy (converter-config/fe-conversion-rate))))))
    (catch Exception e
      (log/error "Error creating Forge Energy capability:" (ex-message e))
      nil)))

(defn register-forge-energy-capability!
  "Register Forge Energy capability for energy converter blocks.

  This allows external mods using Forge Energy to interact with AC converters."
  []
  (try
    ;; Register a Forge Energy capability that wraps our IEnergyCapable
    ;; The capability key :forge-energy will be used to expose ForgeCapabilities.ENERGY
    (platform-cap/declare-capability! :forge-energy IEnergyCapable
      (fn [be side]
        (get-forge-energy-capability be side)))

    ;; Register the capability for energy converter tiles
    (tile-logic/register-tile-capability! "energy-converter" :forge-energy)

    (log/info "Registered Forge Energy capability for energy converters")
    (log/info (format "Conversion rate: 1 IF = %.1f FE" (converter-config/fe-conversion-rate)))
    true
  (catch Exception e
    (log/error "Failed to register Forge Energy capability:" (ex-message e))
    false))))

(defn init-forge-energy!
  "Initialize Forge Energy integration.

  Called during mod initialization to set up capability registration."
  []
  (log/info "Initializing Forge Energy integration...")
  (register-forge-energy-capability!)
  (log/info "Forge Energy integration initialized"))
