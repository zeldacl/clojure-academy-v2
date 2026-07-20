(ns cn.li.fabric1201.datagen.setup
  "Fabric 1.20.1 DataGenerator Setup

   Registers all data generators for JSON generation.

   Fabric uses different event system than Forge, so this module
   provides utilities to be called during data generation phase."
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.fabric1201.datagen.provider-factory :as provider-factory]
            [cn.li.mc1201.datagen.provider-registration :as provider-registration]
            [cn.li.mc1201.datagen.setup-common :as setup-common]))

(def ^:private language-codes
  ["en_us" "zh_cn" "zh_tw" "ja_jp" "ko_kr" "ru_ru"])

(def ^:private providers
  (vec
   (concat
    (map (fn [language-code]
           {:group :lang
            :id (keyword (str "lang-" language-code))
            :label (str "Lang " language-code)
            :summary-label "lang"
            :factory :lang
            :language language-code})
         language-codes)
    [{:group :blockstate
      :id :blockstate
      :label "BlockState"
      :summary-label "blockstate"
      :factory :blockstate}
     {:group :item-model
      :id :item-model
      :label "Item Model"
      :summary-label "item-model"
      :factory :item-model}
     {:group :advancement
      :id :advancement
      :label "Advancement"
      :summary-label "advancement"
      :factory :advancement}
     {:group :recipe
      :id :recipe
      :label "Recipe"
      :summary-label "recipe"
      :factory :recipe}
     {:group :worldgen
      :id :worldgen
      :label "WorldGen"
      :summary-label "worldgen"
      :factory :worldgen}])))


(defn register-data-generators!
  "Register all data generators for Fabric

   Call this during data generation phase."
  [generator _exfile-helper]
  (setup-common/ensure-content-loaded!)
  (let [pack (.createPack ^net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator generator)]
    (provider-registration/register-providers!
      {:mod-id modid/mod-id
       :target-label "fabric-1.20.1"
       :providers providers
       :register-provider! (fn [provider]
                             (provider-factory/add-provider! pack provider))})))
