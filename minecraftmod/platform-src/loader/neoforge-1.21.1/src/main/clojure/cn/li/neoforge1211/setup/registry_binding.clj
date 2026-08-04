(ns cn.li.neoforge1211.setup.registry-binding
  "Forge registry/config registration binding extracted from mod-bus orchestration."
  (:require [cn.li.neoforge1211.config.bridge :as config-bridge]
            [cn.li.neoforge1211.integration.side :as side]
            [cn.li.neoforge1211.setup.deferred-registries :as deferred-registries]
            [cn.li.neoforge1211.setup.lifecycle-listeners :as lifecycle-listeners])
  (:import [cn.li.neoforge1211.entity ModEntities]
           [cn.li.neoforge1211.recipe ModRecipeTypes]
           [cn.li.neoforge1211.trigger ModCriterionTriggers]
           [cn.li.neoforge1211.worldgen ModFeatures]
           [net.neoforged.bus.api IEventBus]))

(defn register-config-phase!
  ([^IEventBus mod-bus _opts]
   (register-config-phase! mod-bus nil _opts))
  ([^IEventBus mod-bus mod-container _opts]
   (config-bridge/register-all! mod-bus mod-container)
   (config-bridge/install-config-persist-op!)
   nil))

(defn register-registry-phase!
  [^IEventBus mod-bus {:keys [datagen-run?
                             sounds-register
                             effects-register
                             particle-types-register
                             fluid-types-register
                             fluids-register
                             blocks-register
                             items-register
                             block-entities-register
                             creative-tabs-register
                             gui-menu-register]}]
  (ModEntities/register mod-bus)
  (ModRecipeTypes/register mod-bus)
  (ModCriterionTriggers/register mod-bus)
  (when (and (side/client-side?) (not datagen-run?))
    (lifecycle-listeners/register-client-hooks!))
  (ModFeatures/register mod-bus)
  (deferred-registries/register-deferred-registries! mod-bus [sounds-register
                                                              effects-register
                                                              particle-types-register
                                                              fluid-types-register
                                                              fluids-register
                                                              blocks-register
                                                              items-register
                                                              block-entities-register
                                                              creative-tabs-register
                                                              gui-menu-register])
  nil)
