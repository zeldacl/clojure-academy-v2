(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.fabric1201.datagen.provider-factory :as provider-factory]
            [cn.li.mc1201.datagen.provider-registration :as provider-registration]
            [cn.li.mc1201.datagen.setup-common :as setup-common]))


(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [generator _exfile-helper]
  (setup-common/ensure-content-loaded!)
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)]
    (provider-registration/register-providers!
      :fabric-1.20.1
      {:mod-id modid/mod-id
       :register-provider! (fn [provider]
                             (provider-factory/add-provider! pack provider))})))
