(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mc1201.datagen.setup-common :as setup-common]
            [cn.li.fabric1201.datagen.lang-provider :as lang-provider]
            [cn.li.fabric1201.datagen.blockstate-provider :as blockstate-provider]
            [cn.li.fabric1201.datagen.item-model-provider :as item-model-provider]
            [cn.li.fabric1201.datagen.advancement-provider :as advancement-provider]
            [cn.li.fabric1201.datagen.recipe-provider :as recipe-provider]))



(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [generator _exfile-helper]
  (setup-common/ensure-ac-content-loaded!)
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)
        lang-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                       (create [_ output]
                         (lang-provider/create-provider output "en_us")))
        zh-lang-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                          (create [_ output]
                            (lang-provider/create-provider output "zh_cn")))
        blockstate-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                             (create [_ output]
                               (blockstate-provider/create-provider output)))
        item-model-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                             (create [_ output]
                               (item-model-provider/create-provider output)))
        advancement-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                              (create [_ output]
                                (advancement-provider/create-provider output)))
        recipe-factory (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
                         (create [_ output]
                           (recipe-provider/create-provider output)))]
    (.addProvider pack lang-factory)
    (.addProvider pack zh-lang-factory)
    (.addProvider pack blockstate-factory)
    (.addProvider pack item-model-factory)
    (.addProvider pack advancement-factory)
    (.addProvider pack recipe-factory)
    (println (str "[" modid/MOD-ID "] Fabric DataGenerator setup registered lang+blockstate+item-model+advancement+recipe providers."))))

(defn create-providers
  "Create all provider instances (for manual registration)"
  [generator _exfile-helper]
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)]
    [(.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (lang-provider/create-provider output "en_us"))))
     (.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (lang-provider/create-provider output "zh_cn"))))
     (.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (blockstate-provider/create-provider output))))
     (.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (item-model-provider/create-provider output))))
     (.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (advancement-provider/create-provider output))))
     (.addProvider pack
       (reify net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator$Pack$Factory
         (create [_ output]
           (recipe-provider/create-provider output))))]))
