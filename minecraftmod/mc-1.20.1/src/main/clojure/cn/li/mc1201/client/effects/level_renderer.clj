(ns cn.li.mc1201.client.effects.level-renderer
  "Shared client level-effect rendering core (Minecraft 1.20.1)."
  (:require [cn.li.mcmod.runtime.hooks-core :as power-runtime])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource RenderType]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.entity.player Abilities]
           [net.minecraft.world.phys Vec3]))

(def ^:private full-bright-uv2 15728880)
(def ^:private default-walk-speed 0.1)
(defonce ^:private last-applied-walk-speed (atom nil))

(defn tick-level-effects!
  []
  (power-runtime/client-tick-level-effects!))

(defn set-local-walk-speed!
  [^LocalPlayer player speed]
  (try
    (let [^Abilities abilities (.getAbilities player)]
      (.setWalkingSpeed abilities (float speed))
      (.onUpdateAbilities player))
    (catch Exception _
      nil)))

(defn apply-local-walk-speed-from-plan!
  [^LocalPlayer player plan]
  (let [target-speed (:local-walk-speed plan)]
    (if (number? target-speed)
      (let [spd (float target-speed)]
        (when (not= @last-applied-walk-speed spd)
          (set-local-walk-speed! player spd)
          (reset! last-applied-walk-speed spd)))
      (when (some? @last-applied-walk-speed)
        (set-local-walk-speed! player default-walk-speed)
        (reset! last-applied-walk-speed nil)))))

(defn hand-center-pos
  [^LocalPlayer player]
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

(defn- emit-line-vertex!
  [^VertexConsumer vc mat x y z r g b a]
  (-> vc
      (.vertex mat (float x) (float y) (float z))
      (.color (int r) (int g) (int b) (int a))
      (.endVertex)))

(defn- emit-line!
  [^VertexConsumer vc mat {:keys [p1 p2 color]}]
  (let [{:keys [r g b a]} color]
    (emit-line-vertex! vc mat (:x p1) (:y p1) (:z p1) r g b a)
    (emit-line-vertex! vc mat (:x p2) (:y p2) (:z p2) r g b a)))

(defn- emit-quad-vertex!
  [^VertexConsumer vc mat p u v color]
  (let [{:keys [r g b a]} color]
    (-> vc
        (.vertex mat (float (:x p)) (float (:y p)) (float (:z p)))
        (.color (int r) (int g) (int b) (int a))
        (.uv (float u) (float v))
        (.overlayCoords (int OverlayTexture/NO_OVERLAY))
        (.uv2 (int full-bright-uv2))
        (.normal 0.0 1.0 0.0)
        (.endVertex))))

(defn- emit-quad!
  [^VertexConsumer vc mat {:keys [p0 p1 p2 p3 u0 u1 v0 v1 color]}]
  (emit-quad-vertex! vc mat p0 u0 v0 color)
  (emit-quad-vertex! vc mat p1 u1 v0 color)
  (emit-quad-vertex! vc mat p2 u1 v1 color)
  (emit-quad-vertex! vc mat p2 u1 v1 color)
  (emit-quad-vertex! vc mat p3 u0 v1 color)
  (emit-quad-vertex! vc mat p0 u0 v0 color))

(defn render-level-plan!
  [{:keys [^LocalPlayer player
           ^PoseStack pose-stack
           ^MultiBufferSource$BufferSource buffer-source
           camera-pos
           tick
           render-plasma-op!]}]
  (let [hand-pos (hand-center-pos player)
        plan (power-runtime/client-build-level-effect-plan camera-pos hand-pos tick)]
    (apply-local-walk-speed-from-plan! player plan)
    (when (seq (:ops plan))
      (let [line-ops (filter #(= (:kind %) :line) (:ops plan))
            quad-ops (filter #(= (:kind %) :quad) (:ops plan))
            plasma-ops (filter #(= (:kind %) :plasma-body) (:ops plan))]
        (.pushPose pose-stack)
        (.translate pose-stack
                    (double (- (:x camera-pos)))
                    (double (- (:y camera-pos)))
                    (double (- (:z camera-pos))))
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
          (when (and render-plasma-op! (seq plasma-ops))
            (doseq [op plasma-ops]
              (render-plasma-op! {:buffer-source buffer-source
                                  :mat mat
                                  :camera-pos camera-pos
                                  :op op})))
          (.popPose pose-stack)
          (.endBatch buffer-source))))
    plan))
