(ns cn.li.fabric1201.datagen.provider-factory
  "Fabric datagen provider factory adapter.

  This namespace is the Fabric-specific shell between the shared provider
  manifest and FabricDataGenerator Pack APIs."
  (:require [cn.li.fabric1201.datagen.advancement-provider :as advancement-provider]
            [cn.li.fabric1201.datagen.item-model-provider :as item-model-provider]
            [cn.li.fabric1201.datagen.lang-provider :as lang-provider]
            [cn.li.fabric1201.datagen.recipe-provider :as recipe-provider]
            [cn.li.mc1201.datagen.blockstate-provider-shell :as blockstate-shell])
  (:import [net.fabricmc.fabric.api.datagen.v1 FabricDataGenerator$Pack$Factory]))

(def ^:private blockstate-provider-name
  "AcademyCraft Fabric Blockstate Provider")

(defn- create-provider
  [provider output]
  (case (:factory provider)
    :lang (lang-provider/create-provider output (:language provider))
    :blockstate (blockstate-shell/create-provider output blockstate-provider-name)
    :item-model (item-model-provider/create-provider output)
    :advancement (advancement-provider/create-provider output)
    :recipe (recipe-provider/create-provider output)
    (throw (ex-info "Unknown Fabric datagen provider factory"
                    {:provider provider}))))

(defn provider-pack-factory
  "Create a Fabric pack provider factory for one shared manifest entry."
  [provider]
  (reify FabricDataGenerator$Pack$Factory
    (create [_ output]
      (create-provider provider output))))

(defn add-provider!
  "Register one shared provider manifest entry with a FabricDataGenerator pack."
  [pack provider]
  (.addProvider pack (provider-pack-factory provider)))
