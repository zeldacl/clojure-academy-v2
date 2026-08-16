(ns cn.li.neoforge262.client.presentation-world-renderer
  "CLIENT-ONLY level effect executor. AC owns the effect state and render plan;
  this loader extracts immutable state, then submits its custom geometry."
  (:require [cn.li.mc262.client.effects.presentation-world :as geometry]
            [cn.li.platform.neutral.vfx :as vfx]
            [cn.li.platform.neutral.presentation :as presentation]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcver ResourceLocations]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer.state.level LevelRenderState]
           [net.minecraft.util.context ContextKey]
           [net.neoforged.neoforge.client.event ExtractLevelRenderStateEvent SubmitCustomGeometryEvent]
           [net.neoforged.neoforge.common NeoForge]
           [net.neoforged.bus.api EventPriority]))

(def ^:private ^ContextKey presentation-context-key
  (ContextKey. (ResourceLocations/of "academy" "presentation_context")))

(defn- camera-position-map
  [^ExtractLevelRenderStateEvent evt]
  (let [position (.position (.getCamera evt))]
    {:x (.-x position)
     :y (.-y position)
     :z (.-z position)}))

(defn- extract-presentation-context!
  [^ExtractLevelRenderStateEvent evt]
  (let [^LevelRenderState render-state (.getRenderState evt)
        ^Minecraft minecraft (Minecraft/getInstance)
        ^LocalPlayer player (when minecraft (.player minecraft))
        camera-pos (camera-position-map evt)
        extracted (when player
                    (vfx/observe-resource-manager!
                     (try (some-> (clojure.lang.Reflector/invokeInstanceMethod
                                   minecraft "getResourceManager" (object-array 0))
                                  System/identityHashCode)
                          (catch Throwable _ nil)))
                    {:player player
                     :camera-pos camera-pos
                     :tick (.-gameTime render-state)
                     :presentation-context
                     (geometry/presentation-frame-context
                       player camera-pos (.-gameTime render-state))})]
    (.setRenderData render-state presentation-context-key extracted)))

(defn- submit-presentation-frame!
  [^SubmitCustomGeometryEvent evt]
  (let [^LevelRenderState render-state (.getLevelRenderState evt)
        extracted (.getRenderData render-state presentation-context-key)]
    (when extracted
      (let [{:keys [^LocalPlayer player camera-pos tick presentation-context]} extracted
            ^Minecraft mc (Minecraft/getInstance)]
        (when mc
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
                         :pose-stack (.getPoseStack evt)
                         :submit-node-collector (.getSubmitNodeCollector evt)}))))}})))))))

(defn- on-extract-level-render-state [^ExtractLevelRenderStateEvent evt]
  (try
    (extract-presentation-context! evt)
    (catch Exception e
      (log/error "Level effect state extraction failed" e)
      (log/stacktrace "Level effect state extraction failed" e))))

(defn- on-submit-custom-geometry [^SubmitCustomGeometryEvent evt]
  (try
    (submit-presentation-frame! evt)
    (catch Exception e
      (log/error "Level effect geometry submission failed" e)
      (log/stacktrace "Level effect geometry submission failed" e))))

(defn init! []
  (install/process-once! ::extract-listener-registered
    #(.addListener (NeoForge/EVENT_BUS)
                   EventPriority/NORMAL false ExtractLevelRenderStateEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-extract-level-render-state evt)))))
  (install/process-once! ::submit-listener-registered
    #(.addListener (NeoForge/EVENT_BUS)
                   EventPriority/NORMAL false SubmitCustomGeometryEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-submit-custom-geometry evt)))))
  (log/info "Level effect renderer initialized"))
