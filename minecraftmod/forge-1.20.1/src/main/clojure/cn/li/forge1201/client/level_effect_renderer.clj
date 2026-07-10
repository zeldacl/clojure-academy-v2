(ns cn.li.forge1201.client.level-effect-renderer
  "CLIENT-ONLY level effect executor. AC owns the effect state and render plan."
  (:require [cn.li.mc1201.client.effects.level-renderer :as shared-level]
            [cn.li.mc1201.runtime.vec-math :as vm]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [cn.li.mc1201.client.render ModRenderTypes]
           [cn.li.forge1201.client.render ModShaders]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]
           [net.minecraftforge.client.event RenderLevelStageEvent]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(def ^:private listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *tick-listener-registered?*
  false)

(def ^:private ^:dynamic *render-listener-registered?*
  false)

(defn- render-stage-eligible? [^RenderLevelStageEvent evt]
  (let [stage-name (str (.getStage evt))]
    (or (.contains stage-name "AFTER_PARTICLES")
        (.contains stage-name "AFTER_TRANSLUCENT"))))

(defn- emit-plasma-vertex! [^VertexConsumer vc mat p]
  (-> vc
      (.vertex mat (float (:x p)) (float (:y p)) (float (:z p)))
      (.endVertex)))

(defn- set-plasma-uniforms!
  [cam-pos {:keys [alpha balls]}]
  (when-let [shader (ModShaders/getPlasmaBodyShader)]
    (let [balls-vec (vec (take 16 (or balls [])))
          ball-count (count balls-vec)]
      (when-let [uniform (.getUniform shader "ballCount")]
        (.set uniform (int ball-count)))
      (when-let [uniform (.getUniform shader "alpha")]
        (.set uniform (float (double (or alpha 0.0)))))
      (doseq [idx (range 16)]
        (when-let [uniform (.getUniform shader (str "balls[" idx "]"))]
          (let [{:keys [x y z size]} (or (nth balls-vec idx nil) {})
                cx (float (- (double (or x 0.0)) (double (:x cam-pos))))
                cy (float (- (double (or y 0.0)) (double (:y cam-pos))))
                cz (float (- (double (or z 0.0)) (double (:z cam-pos))))
                cw (float (double (or size 0.0)))]
            (.set uniform cx cy cz cw)))))))

(defn- emit-plasma-quad!
  [^VertexConsumer vc mat cam-pos {:keys [center radius]}]
  (let [center (or center cam-pos)
        to-cam (vm/normalize (vm/v- cam-pos center))
        world-up {:x 0.0 :y 1.0 :z 0.0}
        right (let [r (vm/cross world-up to-cam)]
                (if (< (+ (Math/abs (double (:x r)))
                          (Math/abs (double (:y r)))
                          (Math/abs (double (:z r))))
                       1.0e-6)
                  {:x 1.0 :y 0.0 :z 0.0}
            (vm/normalize r)))
        up (vm/normalize (vm/cross to-cam right))
        half-size (double (or radius 1.2))
        side (vm/v* right half-size)
        lift (vm/v* up half-size)
        p0 (vm/v+ (vm/v- center side) lift)
        p1 (vm/v+ (vm/v+ center side) lift)
        p2 (vm/v- (vm/v+ center side) lift)
        p3 (vm/v- (vm/v- center side) lift)]
    (emit-plasma-vertex! vc mat p0)
    (emit-plasma-vertex! vc mat p1)
    (emit-plasma-vertex! vc mat p2)
    (emit-plasma-vertex! vc mat p2)
    (emit-plasma-vertex! vc mat p3)
    (emit-plasma-vertex! vc mat p0)))

(defn- render-plasma-op!
  [{:keys [^MultiBufferSource$BufferSource buffer-source mat camera-pos op]}]
  (set-plasma-uniforms! camera-pos op)
  (let [rtype (ModRenderTypes/plasmaBody)
        ^VertexConsumer plasma-vc (.getBuffer buffer-source rtype)]
    (emit-plasma-quad! plasma-vc mat camera-pos op)
    (.endBatch buffer-source rtype)))

(defn- render-level-plan! [^RenderLevelStageEvent evt]
  (when (render-stage-eligible? evt)
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [^LocalPlayer player (.player mc)]
        (let [camera (.getMainCamera (.gameRenderer mc))
              cam-vec (.getPosition camera)
              cam-pos {:x (.-x cam-vec) :y (.-y cam-vec) :z (.-z cam-vec)}
              tick (.getGameTime (.level player))
              ^PoseStack pose-stack (.getPoseStack evt)
              ^MultiBufferSource$BufferSource buffer-source (.bufferSource (.renderBuffers mc))]
          (shared-level/render-level-plan!
           {:player player
            :pose-stack pose-stack
            :buffer-source buffer-source
            :camera-pos cam-pos
            :tick tick
            :render-plasma-op! render-plasma-op!}))))))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (shared-level/tick-level-effects!)))

(defn- on-render-level-stage [^RenderLevelStageEvent evt]
  (try
    (render-level-plan! evt)
    (catch Exception e
      (log/error "Level effect render failed" e)
      (log/stacktrace "Level effect render failed" e))))

(defn init! []
  (when-not (var-get #'*tick-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*tick-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false TickEvent$ClientTickEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-client-tick evt))))
        (alter-var-root #'*tick-listener-registered?* (constantly true)))))
  (when-not (var-get #'*render-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*render-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false RenderLevelStageEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-render-level-stage evt))))
        (alter-var-root #'*render-listener-registered?* (constantly true)))))
  (log/info "Level effect renderer initialized"))