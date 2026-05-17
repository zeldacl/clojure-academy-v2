(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.fabric1201.datagen.lang-provider :as lang-provider]
            [cn.li.fabric1201.datagen.blockstate-provider :as blockstate-provider]
            [cn.li.fabric1201.datagen.item-model-provider :as item-model-provider]
            [cn.li.fabric1201.datagen.advancement-provider :as advancement-provider]
            [cn.li.fabric1201.datagen.recipe-provider :as recipe-provider]
            [cn.li.mc1201.datagen.provider-manifest :as provider-manifest]
            [cn.li.mc1201.datagen.setup-common :as setup-common]))

(defn- create-provider
  [provider output]
  (case (:factory provider)
    :lang (lang-provider/create-provider output (:language provider))
    :blockstate (blockstate-provider/create-provider output)
    :item-model (item-model-provider/create-provider output)
    :advancement (advancement-provider/create-provider output)
    :recipe (recipe-provider/create-provider output)
    (throw (ex-info "Unknown Fabric datagen provider factory"
                    {:provider provider}))))

(defn- fabric-provider-factory
  [provider]
  (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
    (create [_ output]
      (create-provider provider output))))


(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [generator _exfile-helper]
  (setup-common/ensure-ac-content-loaded!)
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)]
    (doseq [provider (provider-manifest/providers-for :fabric-1.20.1)]
      (.addProvider pack (fabric-provider-factory provider)))
    (println (provider-manifest/summary-message modid/MOD-ID :fabric-1.20.1))))
