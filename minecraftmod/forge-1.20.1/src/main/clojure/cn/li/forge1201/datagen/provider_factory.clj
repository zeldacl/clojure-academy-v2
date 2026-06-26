(ns cn.li.forge1201.datagen.provider-factory
  "Forge datagen provider factory adapter.

  This namespace is the Forge-specific shell between the shared provider manifest
  and Forge/Minecraft DataGenerator APIs."
  (:require [cn.li.forge1201.datagen.advancement-provider :as adv]
            [cn.li.forge1201.datagen.item-model-provider :as imp]
            [cn.li.forge1201.datagen.lang-provider :as lang]
            [cn.li.forge1201.datagen.recipe-provider :as rp]
            [cn.li.forge1201.datagen.worldgen-provider :as worldgen]
            [cn.li.mc1201.datagen.blockstate-provider-shell :as blockstate-shell])
  (:import [net.minecraft.data DataGenerator DataProvider$Factory]
           [net.minecraftforge.common.data ExistingFileHelper]))

(def ^:private blockstate-provider-name
  "Forge Blockstate Provider")

(def ^:private provider-factories
  {:blockstate (fn [pack-output _exfile-helper]
                 (blockstate-shell/create-provider pack-output blockstate-provider-name))
   :item-model imp/create
   :lang lang/create
   :recipe rp/create
   :advancement adv/create
   :worldgen worldgen/create})

(defn- provider-factory
  [{:keys [factory] :as provider}]
  (or (get provider-factories factory)
      (throw (ex-info "Unknown Forge datagen provider factory"
                      {:provider provider
                       :known-factories (sort (keys provider-factories))}))))

(defn add-provider!
  "Register one shared provider manifest entry with Forge's DataGenerator."
  [^DataGenerator generator ^ExistingFileHelper exfile-helper provider]
  (let [create-fn (provider-factory provider)]
    (.addProvider generator true
      (reify DataProvider$Factory
        (create [_ pack-output]
          (create-fn pack-output exfile-helper))))))
