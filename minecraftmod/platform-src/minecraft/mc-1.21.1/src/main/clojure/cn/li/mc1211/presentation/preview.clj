(ns cn.li.mc1211.presentation.preview
  "Version-owned callbacks for neutral Presentation model previews."
  (:require [clojure.string :as str])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.world.item ItemStack Item]
           [net.minecraft.world.level.block Block]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.core.registries BuiltInRegistries]))

(defn- stack-for [model-id]
  (let [[kind value] (str/split (str model-id) #":" 2)
        rl (ResourceLocation/tryParse (or value "minecraft:air"))]
    (when rl
      (if (= kind "block")
        (let [^Block block (.get BuiltInRegistries/BLOCK rl)]
          (when block (ItemStack. (.asItem block))))
        (let [^Item item (.get BuiltInRegistries/ITEM rl)]
          (when item (ItemStack. item)))))))

(defn draw-model-preview! [^GuiGraphics graphics _stage model-id x y _width _height]
  (when-let [stack (stack-for model-id)]
    (.renderItem graphics stack (int x) (int y))))

(defn backend-context []
  {:draw-ui-model-preview! draw-model-preview!})