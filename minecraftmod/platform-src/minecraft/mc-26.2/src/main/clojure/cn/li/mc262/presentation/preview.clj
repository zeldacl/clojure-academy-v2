(ns cn.li.mc262.presentation.preview
  "Minecraft 26.2 callback for neutral Presentation model previews.

   26.2 uses the typed Picture-in-Picture render-state seam. The callback
   resolves the requested registry object and delegates submission to the
   version-owned ReactivePreviewRenderState."
  (:require [clojure.string :as str]
            [cn.li.mc262.runtime.registry :as registry])
  (:import [cn.li.mc262.client.render ReactivePreviewRenderState]
           [cn.li.mcver ResourceLocations]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [net.minecraft.world.item Item ItemStack]
           [net.minecraft.world.level.block Block]))

(defn- parse-model-id
  [model-id]
  (let [[kind value] (str/split (str model-id) #":" 2)]
    [kind (or value "minecraft:air")]))

(defn- resolve-item
  ^Item
  [^String item-id]
  (let [[namespace path] (str/split item-id #":" 2)
        id (if path
             (ResourceLocations/of namespace path)
             (ResourceLocations/parse item-id))]
    (.getValue ^net.minecraft.core.Registry (registry/builtin "ITEM") id)))

(defn- resolve-block
  ^Block
  [^String block-id]
  (let [[namespace path] (str/split block-id #":" 2)
        id (if path
             (ResourceLocations/of namespace path)
             (ResourceLocations/parse block-id))]
    (.getValue ^net.minecraft.core.Registry (registry/builtin "BLOCK") id)))

(defn draw-model-preview!
  "Submit one typed 26.2 item/block PIP preview.

   `model-id` is emitted by the neutral presentation compiler as
   `item:<id>` or `block:<id>`. Missing registry values produce no state."
  [^GuiGraphicsExtractor graphics _stage model-id x y width height]
  (let [[kind id] (parse-model-id model-id)]
    (case kind
      "block" (when-let [^Block block (resolve-block id)]
                (ReactivePreviewRenderState/submitBlock
                 graphics block x y width height 1.0 0.0 0.0))
      "item" (when-let [^Item item (resolve-item id)]
               (ReactivePreviewRenderState/submit
                graphics (ItemStack. item) x y width height 1.0 0.0 0.0))
      false)))

(defn backend-context []
  {:draw-ui-model-preview! draw-model-preview!})