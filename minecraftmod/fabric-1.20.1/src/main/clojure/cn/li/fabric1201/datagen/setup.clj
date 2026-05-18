(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.datagen.bootstrap :as ac-datagen]
            [cn.li.fabric1201.datagen.provider-factory :as provider-factory]
            [cn.li.mc1201.datagen.provider-registration :as provider-registration]
            [cn.li.mc1201.datagen.setup-common :as setup-common]))


(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [generator _exfile-helper]
  (setup-common/ensure-content-loaded! "ac")
  (ac-datagen/register-datagen-metadata!)
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)]
    (provider-registration/register-providers!
      :fabric-1.20.1
      {:mod-id modid/MOD-ID
       :register-provider! (fn [provider]
                             (provider-factory/add-provider! pack provider))})))
