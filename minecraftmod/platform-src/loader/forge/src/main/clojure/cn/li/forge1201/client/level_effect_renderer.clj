(ns cn.li.forge1201.client.level-effect-renderer
  "CLIENT-ONLY level effect executor. AC owns the effect state and render plan."
  (:require [cn.li.mc1201.client.effects.level-renderer :as shared-level]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [cn.li.mc1201.client.render ModRenderTypes]
           [cn.li.mcmod.math V3]
           [cn.li.forge1201.client.render ModShaders]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource]
           [net.minecraftforge.client.event RenderLevelStageEvent RenderLevelStageEvent$Stage]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(def ^:private world-up (V3. 0.0 1.0 0.0))
(def ^:private axis-x (V3. 1.0 0.0 0.0))

(defn- map->v3
  "Convert a {:x :y :z} map (crossing from the shared map-based level-effect
  plan context) into a V3 for zero-allocation local math."
  ^V3 [{:keys [x y z]}]
  (V3. (double (or x 0.0)) (double (or y 0.0)) (double (or z 0.0))))

(defn- render-stage-eligible? [^RenderLevelStageEvent evt]
  ;; Stage is a fixed enum-like singleton set (RenderLevelStageEvent$Stage) — identical?
  ;; avoids a fresh string allocation + two substring scans on every stage dispatch
  ;; this tick (~9 stages/frame), most of which are not eligible.
  ;;
  ;; Single stage only: the shared renderer doesn't branch on stage, so
  ;; accepting both AFTER_PARTICLES and AFTER_TRANSLUCENT_BLOCKS ran the
  ;; entire build-plan + vertex-emission pipeline twice per frame and
  ;; double-blended translucent quads. AFTER_TRANSLUCENT_BLOCKS is correct
  ;; for translucent beam/line geometry that must composite after translucent
  ;; terrain (water/glass) — matches Fabric's WorldRenderEvents/AFTER_TRANSLUCENT.
  (identical? (.getStage evt) RenderLevelStageEvent$Stage/AFTER_TRANSLUCENT_BLOCKS))

(defn- emit-plasma-vertex! [^VertexConsumer vc mat ^V3 p]
  (-> vc
      (.vertex mat (float (.-x p)) (float (.-y p)) (float (.-z p)))
      (.endVertex)))

(def ^:private ball-uniform-names
  "Precomputed \"balls[0]\".. \"balls[15]\" uniform names — avoids 16 string
  concatenations per frame in `set-plasma-uniforms!`."
  (mapv (fn [i] (str "balls[" i "]")) (range 16)))

(defn- set-plasma-uniforms!
  [cam-pos {:keys [alpha balls]}]
  (when-let [shader (ModShaders/getPlasmaBodyShader)]
    ;; `balls` may be a lazy seq upstream — vec once so the 16x `nth` below is O(1)
    ;; each, not O(n) per call against a non-indexed seq.
    (let [^V3 cam (map->v3 cam-pos)
          balls-vec (vec (take 16 (or balls [])))
          ball-count (count balls-vec)]
      (when-let [uniform (.getUniform shader "ballCount")]
        (.set uniform (int ball-count)))
      (when-let [uniform (.getUniform shader "alpha")]
        (.set uniform (float (double (or alpha 0.0)))))
      (doseq [idx (range 16)]
        (when-let [uniform (.getUniform shader (nth ball-uniform-names idx))]
          (let [{:keys [x y z size]} (or (nth balls-vec idx nil) {})
                cx (float (- (double (or x 0.0)) (.-x cam)))
                cy (float (- (double (or y 0.0)) (.-y cam)))
                cz (float (- (double (or z 0.0)) (.-z cam)))
                cw (float (double (or size 0.0)))]
            (.set uniform cx cy cz cw)))))))

(defn- emit-plasma-quad!
  "All intermediate vectors are V3 (zero map allocation); `cam-pos`/`center`
  cross the boundary from the shared map-based level-effect plan context, so
  they're converted once per call, not per vector op."
  [^VertexConsumer vc mat cam-pos {:keys [center radius]}]
  (let [^V3 cam (map->v3 cam-pos)
        ^V3 center (if center (map->v3 center) cam)
        to-cam (V3/normalize (V3/sub cam center))
        right-raw (V3/cross world-up to-cam)
        right (if (< (+ (Math/abs (.-x right-raw))
                        (Math/abs (.-y right-raw))
                        (Math/abs (.-z right-raw)))
                     1.0e-6)
                axis-x
                (V3/normalize right-raw))
        up (V3/normalize (V3/cross to-cam right))
        half-size (double (or radius 1.2))
        side (V3/scale right half-size)
        lift (V3/scale up half-size)
        p0 (V3/add (V3/sub center side) lift)
        p1 (V3/add (V3/add center side) lift)
        p2 (V3/sub (V3/add center side) lift)
        p3 (V3/sub (V3/sub center side) lift)]
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
  (install/process-once! ::tick-listener-registered
    #(.addListener (MinecraftForge/EVENT_BUS)
                   EventPriority/NORMAL false TickEvent$ClientTickEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-client-tick evt)))))
  (install/process-once! ::render-listener-registered
    #(.addListener (MinecraftForge/EVENT_BUS)
                   EventPriority/NORMAL false RenderLevelStageEvent
                   (reify java.util.function.Consumer
                     (accept [_ evt] (on-render-level-stage evt)))))
  (log/info "Level effect renderer initialized"))