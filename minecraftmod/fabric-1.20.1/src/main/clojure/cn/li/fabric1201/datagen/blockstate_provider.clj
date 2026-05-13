(ns cn.li.fabric1201.datagen.blockstate-provider
  "Fabric BlockState/BlockModel/ItemModel datagen provider.

  Generates JSON from shared hook-driven blockstate definitions and registry metadata."
  (:require [cn.li.mc1201.datagen.blockstate-provider-shell :as provider-shell])
  (:import [net.minecraft.data PackOutput]))

(defn create-provider
  [^PackOutput output]
  (provider-shell/create-provider output "AcademyCraft Fabric Blockstate Provider"))
