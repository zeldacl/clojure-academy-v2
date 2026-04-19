(ns cn.li.forge1201.client.level-effect-renderer
  "CLIENT-ONLY level effect executor. AC owns the effect state and render plan."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [cn.li.forge1201.client.render ModRenderTypes ModShaders]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource RenderType]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.entity.player Abilities]
           [net.minecraft.world.phys Vec3]
           [net.minecraftforge.client.event RenderLevelStageEvent]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private tick-listener-registered? (atom false))
(defonce ^:private render-listener-registered? (atom false))
(def ^:private full-bright-uv2 15728880)
(def ^:private default-walk-speed 0.1)
(defonce ^:private last-applied-walk-speed (atom nil))

(defn- set-local-walk-speed! [^LocalPlayer player speed]
  (try
    (let [^Abilities abilities (.getAbilities player)]
      (.setWalkingSpeed abilities (float speed))
      (.onUpdateAbilities player))
    (catch Exception _
      nil)))

(defn- apply-local-walk-speed-from-plan! [^LocalPlayer player plan]
  (let [target-speed (:local-walk-speed plan)]
    (if (number? target-speed)
      (let [spd (float target-speed)]
        (when (not= @last-applied-walk-speed spd)
          (set-local-walk-speed! player spd)
          (reset! last-applied-walk-speed spd)))
      (when (some? @last-applied-walk-speed)
        (set-local-walk-speed! player default-walk-speed)
        (reset! last-applied-walk-speed nil)))))

(defn- hand-center-pos [^LocalPlayer player]
  (let [^Vec3 look (.getLookAngle player)
        yaw-rad (Math/toRadians (double (.getYRot player)))
        right-x (Math/cos yaw-rad)
        right-z (Math/sin yaw-rad)
        base-x (.getX player)
        base-y (.getEyeY player)
        base-z (.getZ player)]
    {:player-uuid (str (.getUUID player))
     :x (+ base-x (* (.-x look) 0.35) (* right-x 0.22))
     :y (+ base-y -0.22 (* (.-y look) 0.06))
     :z (+ base-z (* (.-z look) 0.35) (* right-z 0.22))}))

(defn- render-stage-eligible? [^RenderLevelStageEvent evt]
  (let [stage-name (str (.getStage evt))]
    (or (.contains stage-name "AFTER_PARTICLES")
        (.contains stage-name "AFTER_TRANSLUCENT"))))

(defn- emit-line-vertex! [^VertexConsumer vc mat x y z r g b a]
  (-> vc
      (.vertex mat (float x) (float y) (float z))
      (.color (int r) (int g) (int b) (int a))
      (.endVertex)))

(defn- emit-line! [^VertexConsumer vc mat {:keys [p1 p2 color]}]
  (let [{:keys [r g b a]} color]
    (emit-line-vertex! vc mat (:x p1) (:y p1) (:z p1) r g b a)
    (emit-line-vertex! vc mat (:x p2) (:y p2) (:z p2) r g b a)))

(defn- emit-quad-vertex! [^VertexConsumer vc mat p u v color]
  (let [{:keys [r g b a]} color]
    (-> vc
        (.vertex mat (float (:x p)) (float (:y p)) (float (:z p)))
        (.color (int r) (int g) (int b) (int a))
        (.uv (float u) (float v))
        (.overlayCoords (int OverlayTexture/NO_OVERLAY))
        (.uv2 (int full-bright-uv2))
        (.normal 0.0 1.0 0.0)
        (.endVertex))))

(defn- emit-quad! [^VertexConsumer vc mat {:keys [p0 p1 p2 p3 u0 u1 v0 v1 color]}]
  (emit-quad-vertex! vc mat p0 u0 v0 color)
  (emit-quad-vertex! vc mat p1 u1 v0 color)
  (emit-quad-vertex! vc mat p2 u1 v1 color)
  (emit-quad-vertex! vc mat p2 u1 v1 color)
  (emit-quad-vertex! vc mat p3 u0 v1 color)
  (emit-quad-vertex! vc mat p0 u0 v0 color))

(defn- v+
  [a b]
  {:x (+ (:x a) (:x b))
   :y (+ (:y a) (:y b))
   :z (+ (:z a) (:z b))})

(defn- v-
  [a b]
  {:x (- (:x a) (:x b))
   :y (- (:y a) (:y b))
   :z (- (:z a) (:z b))})

(defn- v*
  [a s]
  {:x (* (:x a) s)
   :y (* (:y a) s)
   :z (* (:z a) s)})

(defn- cross
  [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- normalize
  [v]
  (let [len (Math/sqrt (+ (* (:x v) (:x v))
                          (* (:y v) (:y v))
                          (* (:z v) (:z v))))]
    (if (< len 1.0e-6)
      {:x 0.0 :y 1.0 :z 0.0}
      (v* v (/ 1.0 len)))))

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
        to-cam (normalize (v- cam-pos center))
        world-up {:x 0.0 :y 1.0 :z 0.0}
        right (let [r (cross world-up to-cam)]
                (if (< (+ (Math/abs (double (:x r)))
                          (Math/abs (double (:y r)))
                          (Math/abs (double (:z r))))
                       1.0e-6)
                  {:x 1.0 :y 0.0 :z 0.0}
                  (normalize r)))
        up (normalize (cross to-cam right))
        half-size (double (or radius 1.2))
        side (v* right half-size)
        lift (v* up half-size)
        p0 (v+ (v- center side) lift)
        p1 (v+ (v+ center side) lift)
        p2 (v- (v+ center side) lift)
        p3 (v- (v- center side) lift)]
    (emit-plasma-vertex! vc mat p0)
    (emit-plasma-vertex! vc mat p1)
    (emit-plasma-vertex! vc mat p2)
    (emit-plasma-vertex! vc mat p2)
    (emit-plasma-vertex! vc mat p3)
    (emit-plasma-vertex! vc mat p0)))

(defn- render-level-plan! [^RenderLevelStageEvent evt]
  (when (render-stage-eligible? evt)
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [^LocalPlayer player (.player mc)]
        (let [camera (.getMainCamera (.gameRenderer mc))
              cam-vec (.getPosition camera)
              cam-pos {:x (.-x cam-vec) :y (.-y cam-vec) :z (.-z cam-vec)}
              hand-pos (hand-center-pos player)
              tick (.getGameTime (.level player))
              plan (ability-runtime/client-build-level-effect-plan cam-pos hand-pos tick)]
          (apply-local-walk-speed-from-plan! player plan)
          (when (seq (:ops plan))
            (let [^PoseStack pose-stack (.getPoseStack evt)
                  ^MultiBufferSource$BufferSource buffer-source (.bufferSource (.renderBuffers mc))
                  line-ops (filter #(= (:kind %) :line) (:ops plan))
                  quad-ops (filter #(= (:kind %) :quad) (:ops plan))
                  plasma-ops (filter #(= (:kind %) :plasma-body) (:ops plan))]
              (.pushPose pose-stack)
              (.translate pose-stack (double (- (:x cam-pos))) (double (- (:y cam-pos))) (double (- (:z cam-pos))))
              (let [mat (.pose (.last pose-stack))]
                (when (seq line-ops)
                  (let [^VertexConsumer line-vc (.getBuffer buffer-source (RenderType/lines))]
                    (doseq [op line-ops]
                      (emit-line! line-vc mat op))))
                (doseq [[texture ops] (group-by :texture quad-ops)]
                  (when-let [loc (ResourceLocation/tryParse texture)]
                    (let [^VertexConsumer quad-vc (.getBuffer buffer-source (RenderType/entityTranslucent loc))]
                      (doseq [op ops]
                        (emit-quad! quad-vc mat op)))))
                (when (seq plasma-ops)
                  (doseq [op plasma-ops]
                    (set-plasma-uniforms! cam-pos op)
                    (let [rtype (ModRenderTypes/plasmaBody)
                          ^VertexConsumer plasma-vc (.getBuffer buffer-source rtype)]
                      (emit-plasma-quad! plasma-vc mat cam-pos op)
                      (.endBatch buffer-source rtype))))
                (.popPose pose-stack)
                (.endBatch buffer-source)))))))))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (ability-runtime/client-tick-level-effects!)))

(defn- on-render-level-stage [^RenderLevelStageEvent evt]
  (try
    (render-level-plan! evt)
    (catch Exception e
      (log/error "Level effect render failed" e))))

(defn init! []
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (when (compare-and-set! render-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false RenderLevelStageEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-render-level-stage evt)))))
  (log/info "Level effect renderer initialized"))