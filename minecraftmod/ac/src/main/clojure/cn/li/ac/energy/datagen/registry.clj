(ns cn.li.ac.energy.datagen.registry
  "Datagen registry for energy domain.
   Registers shared energy labels and baseline energy type metadata."
  (:require [cn.li.ac.energy.imaginary-energy-impl :as imaginary-energy]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn- energy-translation-map
  []
  {:en_us {"domain.my_mod.energy" "Energy System"
           "energy.my_mod.imaginary" "Imaginary Energy"
           "energy.my_mod.unit.if" "IF"}
   :zh_cn {"domain.my_mod.energy" "能量系统"
           "energy.my_mod.imaginary" "幻想能量"
           "energy.my_mod.unit.if" "IF"}})

(defn register-datagen-metadata!
  "Register energy domain's datagen content.
   Called during datagen initialization phase."
  []
  (imaginary-energy/register-default-energy-type!)
  (let [translation-map (energy-translation-map)]
    (swap! metadata/translations
           (fn [existing]
             (-> existing
                 (update :en_us merge (:en_us translation-map))
                 (update :zh_cn merge (:zh_cn translation-map)))))
    {:domain :energy
     :translations (count (:en_us translation-map))
     :energy-type :imaginary-energy}))
