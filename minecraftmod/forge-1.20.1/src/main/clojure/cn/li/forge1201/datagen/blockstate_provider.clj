(ns cn.li.forge1201.datagen.blockstate-provider
	"Forge BlockState/BlockModel/ItemModel datagen provider.

	Thin platform shell: JSON inference and payload building are shared in mc1201
	blockstate core; this namespace only performs DataProvider IO wiring."
	(:require [cn.li.mc1201.datagen.blockstate-provider-shell :as provider-shell])
	(:import [net.minecraft.data PackOutput]))

(defn create
	[^PackOutput output _exfile-helper]
	(provider-shell/create-provider output "AcademyCraft Forge Blockstate Provider"))