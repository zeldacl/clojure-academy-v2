(ns cn.li.mc1211.presentation.preview
  "Version-owned callbacks for neutral Presentation model previews.

   Recipe slot icons stay flat ItemStack blits. Tutorial :block-3d / large
   :item-3d views keep main's showArea tilt + Y turntable, but are framed in
   GuiGraphics space (same absolute coords as recipe IMAGE/MODEL slots)."
  (:require [clojure.string :as str])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [net.minecraft.world.item ItemStack Item]
           [net.minecraft.world.level.block Block]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex PoseStack]
           [org.joml Quaternionf]))

(defn- parse-model-id [model-id]
  (let [[kind value] (str/split (str model-id) #":" 2)]
    [kind (or value "minecraft:air")]))

(defn- stack-for [model-id]
  (let [[kind value] (parse-model-id model-id)
        rl (ResourceLocation/tryParse value)]
    (when rl
      (if (= kind "block")
        (let [^Block block (.get BuiltInRegistries/BLOCK rl)]
          (when block (ItemStack. (.asItem block))))
        (let [^Item item (.get BuiltInRegistries/ITEM rl)]
          (when item (ItemStack. item)))))))

(defn- draw-flat-item! [^GuiGraphics graphics ^ItemStack stack x y width height]
  (let [ix (int (+ (float x) (/ (- (float width) 16.0) 2.0)))
        iy (int (+ (float y) (/ (- (float height) 16.0) 2.0)))]
    (.renderItem graphics stack ix iy)))

(defn- draw-perspective-preview!
  "Frame the 3D preview in the same GuiGraphics coordinate space as recipe
   IMAGE/MODEL slots. See mc-1.20.1 preview for why this diverges from main's
   GL-viewport camera while keeping tilt/spin parity."
  [^GuiGraphics graphics model-id x y width height]
  (let [[kind id] (parse-model-id model-id)
        ^Minecraft mc (Minecraft/getInstance)
        block? (= kind "block")
        ^ItemStack stack (stack-for model-id)
        cx (float (+ (float x) (/ (float width) 2.0)))
        cy (float (+ (float y) (/ (float height) 2.0)))
        model-px (float (* (min (float width) (float height)) 0.48))
        uni-scale (float (if block? 0.8 1.0))]
    (when stack
      (.flush graphics)
      (let [^PoseStack ps (.pose graphics)]
        (.pushPose ps)
        ;; Absolute pane coords require a clean pose. A leftover GUI translate
        ;; from prior blit/text runs would stack with (cx,cy) and park the ore
        ;; in the preview pane's bottom-right.
        (.setIdentity ps)
        ;; PoseStack.translate is (DDD) — floats do not resolve under direct linking.
        (.translate ps (double cx) (double cy) 100.0)
        (.scale ps model-px (- model-px) model-px)
        (.mulPose ps (doto (Quaternionf.)
                       (.rotateAxis (float (Math/toRadians -20.0)) 1.0 0.0 0.1)))
        (.translate ps 0.15 0.1 0.0)
        (when block?
          (.mulPose ps (doto (Quaternionf.)
                         (.rotateY (float (Math/toRadians
                                            (mod (/ (System/currentTimeMillis) 80.0) 360.0)))))))
        (.scale ps uni-scale uni-scale uni-scale)
        (.translate ps -0.5 -0.5 -0.5)
        (RenderSystem/enableDepthTest)
        (if block?
          (let [rl (ResourceLocation/tryParse id)
                ^Block block (when rl (.get BuiltInRegistries/BLOCK rl))]
            (when block
              (let [state (.defaultBlockState block)
                    brd (.getBlockRenderer mc)
                    buffer (.bufferSource graphics)]
                (.renderSingleBlock brd state ps buffer 15728880
                                    OverlayTexture/NO_OVERLAY)
                (.flush graphics))))
          (do
            (.scale ps (float (/ 1.0 16.0)) (float (/ -1.0 16.0)) (float (/ 1.0 16.0)))
            (.renderFakeItem graphics stack -8 -8)
            (.flush graphics)))
        (RenderSystem/disableDepthTest)
        (.popPose ps)))))

(defn draw-model-preview! [^GuiGraphics graphics _stage model-id x y width height]
  (let [w (float width) h (float height)
        [kind _] (parse-model-id model-id)
        perspective? (or (= kind "block") (and (>= w 64.0) (>= h 64.0)))]
    (if perspective?
      (draw-perspective-preview! graphics model-id x y w h)
      (when-let [stack (stack-for model-id)]
        (draw-flat-item! graphics stack x y w h)))))

(defn backend-context []
  {:draw-ui-model-preview! draw-model-preview!})
