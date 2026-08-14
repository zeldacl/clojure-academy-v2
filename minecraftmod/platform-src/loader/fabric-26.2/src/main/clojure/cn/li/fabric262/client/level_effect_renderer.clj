(ns cn.li.fabric262.client.level-effect-renderer
  "Fabric 26.2 extraction/render hooks for world-space presentation effects."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mc262.client.effects.level-renderer :as level-renderer])
  (:import [net.fabricmc.fabric.api.client.rendering.v1.level LevelExtractionEvents LevelRenderEvents
            LevelExtractionEvents$EndExtraction LevelRenderEvents$AfterTranslucentTerrain
            LevelExtractionContext LevelRenderContext]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.world.phys Vec3]))

(defonce ^:private extracted-plan* (atom nil))

(defn- extract!
  [^LevelExtractionContext context]
  (when-let [^LocalPlayer player (.player (Minecraft/getInstance))]
    (let [^Vec3 camera-pos (.getPosition (.camera context))
          tick (.getGameTime (.level player))]
      (reset! extracted-plan*
              (level-renderer/extract-level-plan!
                {:player player
                 :camera-pos {:x (.x camera-pos) :y (.y camera-pos) :z (.z camera-pos)}
                 :tick tick})))))

(defn- submit!
  [^LevelRenderContext context]
  (when-let [plan @extracted-plan*]
    (try
      (level-renderer/render-level-plan!
        (assoc plan
               :pose-stack (.poseStack context)
               :submit-node-collector (.submitNodeCollector context)))
      (finally
        (reset! extracted-plan* nil)))))


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
