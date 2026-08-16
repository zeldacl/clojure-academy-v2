(ns cn.li.fabric262.client.presentation-world-renderer
  "Fabric 26.2 extraction/render hooks for world-space presentation effects."
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mc262.client.effects.presentation-world :as geometry]
            [cn.li.platform.neutral.vfx :as vfx]
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
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (vfx/observe-resource-manager!
     (try (some-> (clojure.lang.Reflector/invokeInstanceMethod mc "getResourceManager" (object-array 0))
                  System/identityHashCode)
          (catch Throwable _ nil)))
    (when-let [^LocalPlayer player (.player mc)]
      (let [^Vec3 camera-pos (.getPosition (.camera context))
          tick (.getGameTime (.level player))
          camera-map {:x (.x camera-pos) :y (.y camera-pos) :z (.z camera-pos)}]
        (reset! extracted-context*
                {:player player
                 :camera-pos camera-map
                 :tick tick
                 :presentation-context (geometry/presentation-frame-context
                                         player camera-map tick)})))))

(defn- submit!
  [^LevelRenderContext context]
  (when-let [{:keys [^LocalPlayer player camera-pos tick presentation-context]}
             @extracted-context*]
    (try
      (when-let [^Minecraft mc (Minecraft/getInstance)]
        (let [w (.getGuiScaledWidth (.getWindow mc))
              h (.getGuiScaledHeight (.getWindow mc))]
          (presentation/submit-current-frame!
            :world-after-translucent 0.0 w h
            {:presentation-context presentation-context
             :backend-context
             {:draw-batch!
              (fn [_g _stage prim _mat _var _cnt payload]
                (when (= "mesh" prim)
                  (doseq [plan (or payload [])]
                    (geometry/render-presentation-geometry!
                      {:player player :camera-pos camera-pos :tick tick :plan plan
                       :pose-stack (.poseStack context)
                       :submit-node-collector (.submitNodeCollector context)}))))}})))
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
