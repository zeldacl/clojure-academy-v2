(ns cn.li.mc1201.client.effects.level-renderer
  "Shared client level-effect rendering core (Minecraft 1.20.1)."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as power-runtime])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [net.minecraft.client.multiplayer ClientLevel]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource RenderType]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.tags BlockTags]
           [net.minecraft.world.entity.player Abilities]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys Vec3]
           [cn.li.mcmod.math V3]))

(def ^:private full-bright-uv2 15728880)
(def ^:private default-walk-speed 0.1)

(defn create-level-renderer-runtime
  []
  {::runtime ::level-renderer-runtime
   :last-applied-walk-speed* (atom {})})

(def ^:private level-renderer-runtime-atom (atom (create-level-renderer-runtime)))

(defn- level-renderer-runtime?
  [runtime]
  (and (map? runtime)
       (= ::level-renderer-runtime (::runtime runtime))
       (some? (:last-applied-walk-speed* runtime))))

(defn call-with-level-renderer-runtime
  "Set the level renderer runtime for the current context (primarily for testing)."
  [runtime f]
  (when-not (level-renderer-runtime? runtime)
    (throw (ex-info "Expected level renderer runtime"
                    {:runtime runtime})))
  (let [saved @level-renderer-runtime-atom]
    (try
      (reset! level-renderer-runtime-atom runtime)
      (f)
      (finally
        (reset! level-renderer-runtime-atom saved)))))

(defmacro with-level-renderer-runtime
  [runtime & body]
  `(call-with-level-renderer-runtime ~runtime (fn [] ~@body)))

(defn- current-level-renderer-runtime
  []
  @level-renderer-runtime-atom)

(defn- last-applied-walk-speed-atom
  []
  (:last-applied-walk-speed* (current-level-renderer-runtime)))

(defn- walk-speed-owner-key
  [owner]
  (client-session/owner-key owner))

(defn walk-speed-snapshot
  []
  @(last-applied-walk-speed-atom))

(defn reset-walk-speed-for-test!
  ([]
   (reset-walk-speed-for-test! {}))
  ([snapshot]
  (reset! (last-applied-walk-speed-atom) (or snapshot {}))
   nil))

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

(defn clear-owner-walk-speed!
  ([owner]
   (clear-owner-walk-speed! owner nil))
  ([owner ^LocalPlayer player]
   (let [owner-key (walk-speed-owner-key owner)]
     (when (contains? @(last-applied-walk-speed-atom) owner-key)
       (when player
         (set-local-walk-speed! player default-walk-speed))
       (swap! (last-applied-walk-speed-atom) dissoc owner-key)))
   nil))

(defn apply-local-walk-speed-from-plan!
  ([^LocalPlayer player plan]
   (when-let [owner (client-session/current-local-player-owner)]
     (apply-local-walk-speed-from-plan! owner player plan)))
  ([owner ^LocalPlayer player plan]
   (let [owner-key (walk-speed-owner-key owner)
         target-speed (:local-walk-speed plan)]
     (if (number? target-speed)
       (let [spd (double target-speed)]
         (when (not= (get @(last-applied-walk-speed-atom) owner-key) spd)
           (set-local-walk-speed! player spd)
           (swap! (last-applied-walk-speed-atom) assoc owner-key spd)))
       (clear-owner-walk-speed! owner player)))))

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

(defn- block-id-at
  [^ClientLevel level bx by bz]
  (let [pos (BlockPos. (int bx) (int by) (int bz))
        ^BlockState block-state (.getBlockState level pos)
        ^Block block (.getBlock block-state)
        key (.getKey BuiltInRegistries/BLOCK block)]
    (when key (str key))))

(defn- harvest-level-at
  [^ClientLevel level bx by bz]
  (let [pos (BlockPos. (int bx) (int by) (int bz))
        ^BlockState block-state (.getBlockState level pos)]
    (cond
      (.is block-state BlockTags/NEEDS_DIAMOND_TOOL) 3
      (.is block-state BlockTags/NEEDS_IRON_TOOL) 2
      (.is block-state BlockTags/NEEDS_STONE_TOOL) 1
      :else 0)))

(defn- make-nearby-block-query-fn
  [^LocalPlayer player]
  (fn [x y z radius block-predicate]
    (try
      (let [level (.level player)
            base-x (int (Math/floor (double x)))
            base-y (int (Math/floor (double y)))
            base-z (int (Math/floor (double z)))
            r (int (Math/ceil (double radius)))]
        (if (or (nil? level) (<= r 0))
          []
          (loop [dx (- r)
                 acc []]
            (if (> dx r)
              acc
              (recur (inc dx)
                     (loop [dy (- r)
                            acc2 acc]
                       (if (> dy r)
                         acc2
                         (recur (inc dy)
                                (loop [dz (- r)
                                       acc3 acc2]
                                  (if (> dz r)
                                    acc3
                                    (let [dist (Math/sqrt (double (+ (* dx dx) (* dy dy) (* dz dz))))]
                                      (if (> dist (double radius))
                                        (recur (inc dz) acc3)
                                        (let [bx (+ base-x dx)
                                              by (+ base-y dy)
                                              bz (+ base-z dz)
                                              block-id (block-id-at level bx by bz)]
                                          (if (and block-id (block-predicate block-id))
                                            (recur (inc dz) (conj acc3 {:x bx
                                                                        :y by
                                                                        :z bz
                                                                        :block-id block-id
                                                                        :harvest-level (harvest-level-at level bx by bz)}))
                                            (recur (inc dz) acc3)))))))))))))))
      (catch Exception _
        []))))

(defn- color-int→channels
  "Render ops store colours as packed ARGB ints (from arc/pattern-color);
   Minecraft's VertexConsumer expects separate RGBA int channels."
  [color-int]
  (let [c (long color-int)]
    [(unchecked-int (bit-and (bit-shift-right c 24) 0xFF))      ;; a
     (unchecked-int (bit-and (bit-shift-right c 16) 0xFF))      ;; r
     (unchecked-int (bit-and (bit-shift-right c 8)  0xFF))      ;; g
     (unchecked-int (bit-and c 0xFF))]))                         ;; b

(defn- emit-line-vertex!
  [^VertexConsumer vc mat x y z r g b a]
  (-> vc
      (.vertex mat (float x) (float y) (float z))
      (.color (int r) (int g) (int b) (int a))
      (.normal 0.0 1.0 0.0)
      (.endVertex)))

(defn- emit-line!
  [^VertexConsumer vc mat {:keys [^V3 p1 ^V3 p2 color]}]
  (let [[a r g b] (color-int→channels color)]
    (emit-line-vertex! vc mat (.-x p1) (.-y p1) (.-z p1) r g b a)
    (emit-line-vertex! vc mat (.-x p2) (.-y p2) (.-z p2) r g b a)))

(defn- emit-quad-vertex!
  [^VertexConsumer vc mat ^V3 p u v color]
  (let [[a r g b] (color-int→channels color)]
    (-> vc
        (.vertex mat (float (.-x p)) (float (.-y p)) (float (.-z p)))
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

(defn- sort-ops
  "Single pass over `ops`, bucketing into {:lines [...] :quads {texture [...]}
  :plasma [...]} — replaces 3 filters + a group-by (4 traversals plus the
  lazy-seq allocations each filter produces) with one reduce."
  [ops]
  (reduce
    (fn [acc op]
      (case (:kind op)
        :line (update acc :lines conj op)
        :quad (update acc :quads update (:texture op) (fnil conj []) op)
        :plasma-body (update acc :plasma conj op)
        acc))
    {:lines [] :quads {} :plasma []}
    ops))

(defn render-level-plan!
  [{:keys [^LocalPlayer player
           ^PoseStack pose-stack
           ^MultiBufferSource$BufferSource buffer-source
           camera-pos
           tick
           render-plasma-op!]}]
  (let [owner (client-session/current-local-player-owner)
        ;; Skip hand-center-pos/query-fn allocation and the plan build itself
        ;; when no level effect is active (idle skill) — checked first so the
        ;; common (idle) frame does none of the below.
        plan (when (power-runtime/client-level-effects-active?)
               (power-runtime/client-build-level-effect-plan
                 camera-pos (hand-center-pos player) tick (make-nearby-block-query-fn player)))]
    (when owner
      (apply-local-walk-speed-from-plan! owner player plan))
    (when (seq (:ops plan))
      (let [{:keys [lines quads plasma]} (sort-ops (:ops plan))]
        (.pushPose pose-stack)
        (.translate pose-stack
                    (double (- (:x camera-pos)))
                    (double (- (:y camera-pos)))
                    (double (- (:z camera-pos))))
        (let [mat (.pose (.last pose-stack))]
          (when (seq lines)
            (let [^VertexConsumer line-vc (.getBuffer buffer-source (RenderType/lines))]
              (doseq [op lines]
                (emit-line! line-vc mat op))))
          (doseq [[texture texture-ops] quads]
            (when-let [loc (ResourceLocation/tryParse texture)]
              (let [^VertexConsumer quad-vc (.getBuffer buffer-source (RenderType/entityTranslucent loc))]
                (doseq [op texture-ops]
                  (emit-quad! quad-vc mat op)))))
          (when (and render-plasma-op! (seq plasma))
            (doseq [op plasma]
              (render-plasma-op! {:buffer-source buffer-source
                                  :mat mat
                                  :camera-pos camera-pos
                                  :op op})))
          (.popPose pose-stack)
          (.endBatch buffer-source))))
    plan))
