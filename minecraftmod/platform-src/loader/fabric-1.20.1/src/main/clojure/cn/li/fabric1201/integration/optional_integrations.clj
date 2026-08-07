(ns cn.li.fabric1201.integration.optional-integrations
  "Optional Fabric integrations kept behind one loader seam.

  The shared runtime owns callback registration; this namespace only detects
  optional modules and records their availability so content code never takes a
  hard dependency on Energy or JEI classes."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.fabricbase FabricIntegration]))

(defn init!
  []
  (log/info "Fabric optional integrations"
            {:energy (FabricIntegration/isModLoaded "team_reborn_energy")
             :jei (FabricIntegration/isModLoaded "jei")})
  nil)
