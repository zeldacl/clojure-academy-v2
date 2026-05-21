(ns cn.li.ac.core.init
  "AC core initialization orchestration extracted from cn.li.ac.core."
  (:require [cn.li.ac.ability.adapters.runtime-bridge :as ability-runtime]
            [cn.li.ac.block.platform-bridge :as block-bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.config.registry :as config-registry]
            [cn.li.mcmod.util.log :as log]))

(defn init
  "Core init hook invoked by per-version entry classes."
  []
  (modid/install-modid!)
  (log/info "Initializing core for mod-id=" modid/MOD-ID)
  (block-bridge/install-blockstate-hooks!)
  (when-let [init-wireless-world-data! (requiring-resolve 'cn.li.ac.wireless.data.world/init-world-data!)]
    (init-wireless-world-data!))
  (config-registry/init-configs!)
  (ability-runtime/install-runtime-hooks!)
  nil)
