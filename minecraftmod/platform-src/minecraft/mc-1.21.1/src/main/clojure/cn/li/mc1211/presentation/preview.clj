(ns cn.li.mc1211.presentation.preview
  "Version-owned callbacks for neutral Presentation model previews.

   Recipe slot icons stay flat ItemStack blits. Tutorial :block-3d / large
   :item-3d views use the upstream perspective camera (FOV 50, Y turntable)."
  (:require [clojure.string :as str])
  (:import [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.client Minecraft]
           [net.minecraft.world.item ItemStack Item]
           [net.minecraft.world.level.block Block]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [com.mojang.blaze3d.systems RenderSystem]
           [com.mojang.blaze3d.vertex PoseStack VertexSorting]
           [org.joml Matrix4f Quaternionf]))

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
  [^GuiGraphics graphics model-id x y width height]
  (let [[kind id] (parse-model-id model-id)
        ^Minecraft mc (Minecraft/getInstance)
        win (.getWindow mc)
        gui-scale (.getGuiScale win)
        fb-w (.getWidth win)
        fb-h (.getHeight win)
        vp-x (int (Math/round (* x gui-scale)))
        vp-w (int (max 1 (Math/round (* width gui-scale))))
        vp-h (int (max 1 (Math/round (* height gui-scale))))
        vp-y (int (Math/round (- fb-h (* (+ y height) gui-scale))))
        aspect (float (if (pos? height) (/ width height) 1.0))
        persp (-> (Matrix4f.)
                  (.scaling (float 1.0) (float -1.0) (float -0.5))
                  (.perspective (float (Math/toRadians 50.0)) aspect
                                (float 1.0) (float 100.0)))
        ^PoseStack mv (RenderSystem/getModelViewStack)
        saved-proj (RenderSystem/getProjectionMatrix)
        block? (= kind "block")
        ^ItemStack stack (stack-for model-id)]
    (when stack
      (.flush graphics)
      (RenderSystem/viewport vp-x vp-y vp-w vp-h)
      (RenderSystem/setProjectionMatrix persp VertexSorting/DISTANCE_TO_ORIGIN)
      (.pushPose mv)
      (.setIdentity mv)
      (.translate mv 0.0 0.0 -4.0)
      (.translate mv 0.55 0.55 0.5)
      (.scale mv 0.75 -0.75 0.75)
      (.mulPose mv (doto (Quaternionf.)
                     (.rotateAxis (float (Math/toRadians -20.0)) 1.0 0.0 0.1)))
      (.translate mv 0.15 0.1 -1.0)
      (when block?
        (.mulPose mv (doto (Quaternionf.)
                       (.rotateY (float (Math/toRadians
                                          (mod (/ (System/currentTimeMillis) 80.0) 360.0)))))))
      (.scale mv (float (if block? 0.8 1.0)) (float (if block? 0.8 1.0)) (float (if block? 0.8 1.0)))
      (.translate mv -0.5 -0.5 -0.5)
      (RenderSystem/applyModelViewMatrix)
      (if block?
        (let [rl (ResourceLocation/tryParse id)
              ^Block block (when rl (.get BuiltInRegistries/BLOCK rl))]
          (when block
            (let [state (.defaultBlockState block)
                  brd (.getBlockRenderer mc)
                  buffer (.bufferSource graphics)]
              (RenderSystem/enableDepthTest)
              (.renderSingleBlock brd state (.pose graphics) buffer 15728880
                                  OverlayTexture/NO_OVERLAY)
              (.flush graphics)
              (RenderSystem/disableDepthTest))))
        (let [^PoseStack ps (.pose graphics)]
          (.pushPose ps)
          (.scale ps (float (/ 1.0 16.0)) (float (/ -1.0 16.0)) (float (/ 1.0 16.0)))
          (.translate ps 0.0 0.0 -150.0)
          (.renderFakeItem graphics stack -8 -8)
          (.flush graphics)
          (.popPose ps)))
      (.popPose mv)
      (RenderSystem/applyModelViewMatrix)
      (RenderSystem/setProjectionMatrix saved-proj VertexSorting/DISTANCE_TO_ORIGIN)
      (RenderSystem/viewport 0 0 fb-w fb-h))))

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
