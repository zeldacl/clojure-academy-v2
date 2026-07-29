(ns cn.li.mc1201.client.effects.level-renderer
  "Shared client level-effect rendering core (Minecraft 1.20.1)."
  (:require [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as power-runtime])
  (:import [com.mojang.blaze3d.vertex PoseStack VertexConsumer]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.client.renderer MultiBufferSource$BufferSource RenderType]
           [net.minecraft.client.renderer.texture OverlayTexture]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.tags BlockTags TagKey]
           [net.minecraft.world.entity.player Abilities]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys Vec3]
           [cn.li.mcmod.math V3]))

(def ^:private full-bright-uv2 15728880)
(def ^:private default-walk-speed 0.1)
(def ^:private conventional-ore-tags*
  ;; Forge 1.20.1 and Fabric's common convention use different root ore tags.
  ;; Checking both recreates the original OreDictionary "ore" lookup without
  ;; importing either loader API into the shared Minecraft layer. Delay their
  ;; construction because touching Registries/BLOCK during AOT compilation
  ;; triggers Minecraft's bootstrap guard.
  (delay
    [(TagKey/create Registries/BLOCK (ResourceLocation. "forge:ores"))
     (TagKey/create Registries/BLOCK (ResourceLocation. "c:ores"))]))

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

(defn- local-camera-first-person?
  "True while the local camera is in first person (F5 not toggled out).

  This is the port of the original's ViewOptimize.isFirstPerson gameSettings
  half; effect code pairs it with an is-this-my-effect check on the uuid.
  Both loaders' level-effect renderers call render-level-plan! below, so
  reading it here from Minecraft's own options is all the wiring it needs."
  []
  (if-let [^Minecraft mc (Minecraft/getInstance)]
    (.isFirstPerson (.getCameraType (.-options mc)))
    true))

(defn hand-center-pos
  "Local player's hand position, plus the view context effect code needs to
  recognise its own player's effects (`:player-uuid`) and which of the
  original's two ViewOptimize offsets applies (`:first-person?`)."
  [^LocalPlayer player]
  (let [^Vec3 look (.getLookAngle player)
        yaw-rad (Math/toRadians (double (.getYRot player)))
        right-x (Math/cos yaw-rad)
        right-z (Math/sin yaw-rad)
        base-x (.getX player)
        base-y (.getEyeY player)
        base-z (.getZ player)]
    {:player-uuid (str (.getUUID player))
     :first-person? (local-camera-first-person?)
     :player-x base-x
     :player-y (.getY player)
     :player-z base-z
     :player-width (.getBbWidth player)
     :player-height (.getBbHeight player)
     :player-yaw-rad yaw-rad
     :x (+ base-x (* (.-x look) 0.35) (* right-x 0.22))
     :y (+ base-y -0.22 (* (.-y look) 0.06))
     :z (+ base-z (* (.-z look) 0.35) (* right-z 0.22))}))

(defn- block-id-for-state
  [^BlockState block-state]
  (let [^Block block (.getBlock block-state)
        key (.getKey BuiltInRegistries/BLOCK block)]
    (when key (str key))))

(defn- harvest-level-for-state
  [^BlockState block-state]
  (cond
    (.is block-state BlockTags/NEEDS_DIAMOND_TOOL) 3
    (.is block-state BlockTags/NEEDS_IRON_TOOL) 2
    (.is block-state BlockTags/NEEDS_STONE_TOOL) 1
    :else 0))

(defn- conventionally-tagged-ore?
  [^BlockState block-state]
  (boolean
    (some (fn [^TagKey tag-key]
            (.is block-state tag-key))
          @conventional-ore-tags*)))

(defn- block-predicate-matches?
  [block-predicate block-id metadata]
  (try
    (boolean (block-predicate block-id metadata))
    (catch clojure.lang.ArityException _
      ;; Preserve the original one-argument nearby-block query contract for
      ;; effects that do not consume platform metadata.
      (boolean (block-predicate block-id)))))

(defn- make-nearby-block-query-fn
  [^LocalPlayer player]
  (fn [x y z radius block-predicate]
    (try
      (let [level (.level player)
            x* (double x)
            y* (double y)
            z* (double z)
            radius* (double radius)
            radius-sq (* radius* radius*)
            min-x (int (Math/floor (- x* radius*)))
            min-y (int (Math/floor (- y* radius*)))
            min-z (int (Math/floor (- z* radius*)))
            max-x (int (Math/ceil (+ x* radius*)))
            max-y (int (Math/ceil (+ y* radius*)))
            max-z (int (Math/ceil (+ z* radius*)))]
        (if (or (nil? level) (not (pos? radius*)))
          []
          (loop [bx min-x
                 acc []]
            (if (> bx max-x)
              acc
              (recur (inc bx)
                     (loop [by min-y
                            acc2 acc]
                       (if (> by max-y)
                         acc2
                         (recur (inc by)
                                (loop [bz min-z
                                       acc3 acc2]
                                  (if (> bz max-z)
                                    acc3
                                    (let [dx (- (double bx) x*)
                                          dy (- (double by) y*)
                                          dz (- (double bz) z*)
                                          dist-sq (+ (* dx dx) (* dy dy) (* dz dz))]
                                      (if (> dist-sq radius-sq)
                                        (recur (inc bz) acc3)
                                        (let [pos (BlockPos. bx by bz)
                                              ^BlockState block-state (.getBlockState level pos)
                                              block-id (block-id-for-state block-state)
                                              metadata {:ore-tagged? (conventionally-tagged-ore? block-state)}]
                                          (if (and block-id
                                                   (block-predicate-matches? block-predicate block-id metadata))
                                            (recur (inc bz)
                                                   (conj acc3 {:x bx
                                                               :y by
                                                               :z bz
                                                               :block-id block-id
                                                               :harvest-level (harvest-level-for-state block-state)}))
                                            (recur (inc bz) acc3)))))))))))))))
      (catch Exception _
        []))))

(defn- color-channel-255
  "Normalize one color channel to an int in [0,255].

  Accepts either byte-style values (0..255) or unit values (0.0..1.0)."
  [v default-v]
  (let [raw (if (number? v) (double v) (double default-v))
        scaled (if (<= 0.0 raw 1.0)
                 (* raw 255.0)
                 raw)
        clamped (max 0.0 (min 255.0 scaled))]
    (int (Math/round clamped))))

(defn- map-color->channels
  [m]
  (let [r (or (:r m) (get m "r"))
        g (or (:g m) (get m "g"))
        b (or (:b m) (get m "b"))
        a (or (:a m) (get m "a") 255)]
    [(color-channel-255 a 255)
     (color-channel-255 r 255)
     (color-channel-255 g 255)
     (color-channel-255 b 255)]))

(defn- vec-color->channels
  [color-vec]
  (let [[r g b a] (concat (take 4 color-vec) [nil nil nil 255])]
    [(color-channel-255 a 255)
     (color-channel-255 r 255)
     (color-channel-255 g 255)
     (color-channel-255 b 255)]))

(defn- color-int→channels
  "Render ops may carry color as packed ARGB int or legacy RGBA map/vector.
   Minecraft's VertexConsumer expects separate RGBA int channels."
  [color]
  (cond
    (number? color)
    (let [c (long color)]
      [(unchecked-int (bit-and (bit-shift-right c 24) 0xFF))      ;; a
       (unchecked-int (bit-and (bit-shift-right c 16) 0xFF))      ;; r
       (unchecked-int (bit-and (bit-shift-right c 8)  0xFF))      ;; g
       (unchecked-int (bit-and c 0xFF))])                         ;; b

    (map? color)
    (map-color->channels color)

    (sequential? color)
    (vec-color->channels color)

    :else
    [255 255 255 255]))

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
  "Emit ONE quad as exactly 4 vertices.

  The buffer comes from RenderType/entityTranslucent, whose vertex mode is
  QUADS: the builder slices the stream into primitives every 4 vertices,
  ignoring where each op started. Emitting the 6-vertex two-triangle form
  here pushed every following op out of phase with that grouping, so quads
  got assembled from a mix of one op's trailing corners and the next op's
  leading ones — the arc rendered as scattered garbage polygons instead of
  ribbons.

  p0/p1 are the two corners at the segment's start and p2/p3 at its end (see
  render-util's beam quads), so `u` runs along the beam and `v` across its
  width."
  [^VertexConsumer vc mat {:keys [p0 p1 p2 p3 u0 u1 v0 v1 color]}]
  (emit-quad-vertex! vc mat p0 u0 v0 color)
  (emit-quad-vertex! vc mat p1 u0 v1 color)
  (emit-quad-vertex! vc mat p2 u1 v1 color)
  (emit-quad-vertex! vc mat p3 u1 v0 color))

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
