(ns cn.li.fabric262.client.level-effect-renderer
  "Fabric 26.2 extraction/render hooks for world-space presentation effects."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mc262.client.effects.level-renderer :as geometry]
            [cn.li.platform.neutral.presentation :as presentation])
  (:import [net.fabricmc.fabric.api.client.rendering.v1.level LevelExtractionEvents LevelRenderEvents
            LevelExtractionEvents$EndExtraction LevelRenderEvents$AfterTranslucentTerrain
            LevelExtractionContext LevelRenderContext]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.world.phys Vec3]))

(defonce ^:private extracted-context* (atom nil))

(defn- extract!
  [^LevelExtractionContext context]
  (when-let [^LocalPlayer player (.player (Minecraft/getInstance))]
    (let [^Vec3 camera-pos (.getPosition (.camera context))
          tick (.getGameTime (.level player))
          camera-map {:x (.x camera-pos) :y (.y camera-pos) :z (.z camera-pos)}]
      (reset! extracted-context*
              {:player player
               :camera-pos camera-map
               :tick tick
               :presentation-context (geometry/presentation-frame-context
                                       player camera-map tick)}))))

(defn- submit!
  [^LevelRenderContext context]
  (when-let [{:keys [^LocalPlayer player camera-pos tick presentation-context]}
             @extracted-context*]
    (try
      (presentation/submit-current-frame!
        :world-after-translucent 0.0 0 0
        {:presentation-context presentation-context
         :backend-context
         {:draw-mesh!
          (fn [_ _ _ _ _ payload]
            (geometry/render-presentation-geometry!
              {:player player :camera-pos camera-pos :tick tick :plan payload
               :pose-stack (.poseStack context)
               :submit-node-collector (.submitNodeCollector context)}))}})
      (finally
        (reset! extracted-context* nil)))))


(defn init! []
  (install/process-once!
    :fabric262/presentation-level-effects
    #(do
       (.register LevelExtractionEvents/END_EXTRACTION
                  (reify LevelExtractionEvents$EndExtraction
                    (endExtraction [_ context] (extract! context))))
       (.register LevelRenderEvents/AFTER_TRANSLUCENT_TERRAIN
                  (reify LevelRenderEvents$AfterTranslucentTerrain
                    (afterTranslucentTerrain [_ context] (submit! context))))))
  nil)
