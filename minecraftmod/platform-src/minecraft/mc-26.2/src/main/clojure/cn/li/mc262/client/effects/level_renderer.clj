(ns cn.li.mc262.client.effects.level-renderer
  "Shared client level-effect core for Minecraft 26.2.

   Effect plans are extracted before level submission and emitted through
   SubmitNodeCollector, with all GPU state owned by RenderType."
  (:require [cn.li.mc262.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as power-runtime])
  (:import [com.mojang.blaze3d.vertex PoseStack PoseStack$Pose VertexConsumer]
           [cn.li.mc262.client.effects LevelEffectGeometry]
           [cn.li.mcmod.math V3]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]
           [net.minecraft.client.renderer SubmitNodeCollector SubmitNodeCollector$CustomGeometryRenderer]
           [net.minecraft.client.renderer.rendertype RenderType RenderTypes]
           [net.minecraft.core BlockPos]
           [net.minecraft.core.registries BuiltInRegistries Registries]
           [net.minecraft.resources Identifier]
           [net.minecraft.tags BlockTags TagKey]
           [net.minecraft.world.entity.player Abilities]
           [net.minecraft.world.level.block Block]
           [net.minecraft.world.level.block.state BlockState]
           [net.minecraft.world.phys Vec3]
           [cn.li.mcver ResourceLocations]))

(def ^:private default-walk-speed 0.1)
(def ^:private conventional-ore-tags*
  (delay
    [(TagKey/create Registries/BLOCK (ResourceLocations/parse "c:ores"))
     (TagKey/create Registries/BLOCK (ResourceLocations/parse "forge:ores"))
     (TagKey/create Registries/BLOCK (ResourceLocations/parse "neoforge:ores"))]))

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

(defn current-fov-offset
  [player-uuid]
  (power-runtime/client-level-effect-fov-offset player-uuid))

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
  []
  (if-let [^Minecraft mc (Minecraft/getInstance)]
    (.isFirstPerson (.getCameraType (.-options mc)))
    true))

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
     :first-person? (local-camera-first-person?)
     :player-x base-x
     :player-y (.getY player)
     :player-z base-z
     :player-width (.getBbWidth player)
     :player-height (.getBbHeight player)
     :player-yaw-rad yaw-rad
     :player-body-yaw-rad (Math/toRadians (double (.-yBodyRot player)))
     :player-pitch-rad (Math/toRadians (double (.getXRot player)))
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
      (boolean (block-predicate block-id)))))

(defn make-nearby-block-query-fn
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
  [v]
  (let [r (nth v 0 255)
        g (nth v 1 255)
        b (nth v 2 255)
        a (nth v 3 255)]
    [(color-channel-255 a 255)
     (color-channel-255 r 255)
     (color-channel-255 g 255)
     (color-channel-255 b 255)]))

(defn- color-int→channels
  [color]
  (let [c (int color)]
    [(bit-and (unsigned-bit-shift-right c 24) 0xff)
     (bit-and (unsigned-bit-shift-right c 16) 0xff)
     (bit-and (unsigned-bit-shift-right c 8) 0xff)
     (bit-and c 0xff)]))

(defn- emit-line-vertex!
  [^VertexConsumer consumer ^PoseStack$Pose pose x y z r g b a]
  (LevelEffectGeometry/lineVertex
    consumer pose (float x) (float y) (float z)
    (int r) (int g) (int b) (int a)))

(defn- emit-line!
  [^VertexConsumer consumer ^PoseStack$Pose pose {:keys [^V3 p1 ^V3 p2 color]}]
  (let [[a r g b] (color-int→channels color)]
    (emit-line-vertex! consumer pose (.-x p1) (.-y p1) (.-z p1) r g b a)
    (emit-line-vertex! consumer pose (.-x p2) (.-y p2) (.-z p2) r g b a)))

(defn- emit-quad-vertex!
  [^VertexConsumer consumer ^PoseStack$Pose pose ^V3 p u v color]
  (let [[a r g b] (color-int→channels color)]
    (LevelEffectGeometry/texturedVertex
      consumer pose
      (float (.-x p)) (float (.-y p)) (float (.-z p))
      (float u) (float v)
      (int r) (int g) (int b) (int a))))

(defn- emit-quad!
  [^VertexConsumer consumer ^PoseStack$Pose pose
   {:keys [p0 p1 p2 p3 u0 u1 v0 v1 color]}]
  (emit-quad-vertex! consumer pose p0 u0 v0 color)
  (emit-quad-vertex! consumer pose p1 u0 v1 color)
  (emit-quad-vertex! consumer pose p2 u1 v1 color)
  (emit-quad-vertex! consumer pose p3 u1 v0 color))

(defn- sort-ops
  [ops]
  (let [lines (transient [])
        plasma (transient [])
        ^java.util.HashMap quads-t (java.util.HashMap.)]
    (doseq [op ops]
      (case (:kind op)
        :line (conj! lines op)
        :quad (let [texture (:texture op)
                    bucket (or (.get quads-t texture)
                               (let [created (transient [])]
                                 (.put quads-t texture created)
                                 created))]
                (conj! bucket op))
        :plasma-body (conj! plasma op)
        nil))
    {:lines (persistent! lines)
     :quads (persistent!
              (reduce (fn [result ^java.util.Map$Entry entry]
                        (assoc! result (.getKey entry)
                                (persistent! (.getValue entry))))
                      (transient {})
                      (.entrySet quads-t)))
     :plasma (persistent! plasma)}))

(defn- map->v3
  ^V3 [{:keys [x y z]}]
  (V3. (double (or x 0.0))
       (double (or y 0.0))
       (double (or z 0.0))))

(def ^:private world-up (V3. 0.0 1.0 0.0))
(def ^:private axis-x (V3. 1.0 0.0 0.0))

(defn- billboard-basis
  [camera-pos center]
  (let [^V3 camera (map->v3 camera-pos)
        ^V3 center-v3 (map->v3 center)
        to-camera (V3/normalize (V3/sub camera center-v3))
        right-raw (V3/cross world-up to-camera)
        right (if (< (+ (Math/abs (.-x right-raw))
                        (Math/abs (.-y right-raw))
                        (Math/abs (.-z right-raw)))
                     1.0e-6)
                axis-x
                (V3/normalize right-raw))
        up (V3/normalize (V3/cross to-camera right))]
    [center-v3 right up]))

(defn- emit-colored-vertex!
  [^VertexConsumer consumer ^PoseStack$Pose pose ^V3 p r g b a]
  (LevelEffectGeometry/coloredVertex
    consumer pose
    (float (.-x p)) (float (.-y p)) (float (.-z p))
    (int r) (int g) (int b) (int a)))

(defn- emit-plasma-ball!
  [^VertexConsumer consumer ^PoseStack$Pose pose camera-pos
   {:keys [x y z size] :as ball} alpha]
  (let [[^V3 center ^V3 right ^V3 up] (billboard-basis camera-pos ball)
        radius (max 0.05 (double (or size 0.5)))
        side (V3/scale right radius)
        lift (V3/scale up radius)
        p0 (V3/add (V3/sub center side) lift)
        p1 (V3/add (V3/add center side) lift)
        p2 (V3/sub (V3/add center side) lift)
        p3 (V3/sub (V3/sub center side) lift)]
    (emit-colored-vertex! consumer pose p0 110 225 255 alpha)
    (emit-colored-vertex! consumer pose p1 70 175 255 alpha)
    (emit-colored-vertex! consumer pose p2 40 110 255 alpha)
    (emit-colored-vertex! consumer pose p3 70 175 255 alpha)))

(defn- emit-plasma-op!
  [^VertexConsumer consumer ^PoseStack$Pose pose camera-pos
   {:keys [center radius alpha balls]}]
  (let [alpha-channel (color-channel-255 alpha 1.0)
        visible-balls (or (seq balls)
                          [(assoc (or center {:x 0.0 :y 0.0 :z 0.0})
                                  :size (double (or radius 0.75)))])]
    (doseq [ball visible-balls]
      (emit-plasma-ball! consumer pose camera-pos ball alpha-channel))))

(defn- submit-custom-geometry!
  [^SubmitNodeCollector collector ^PoseStack pose-stack
   ^RenderType render-type render-fn]
  (.submitCustomGeometry
    collector pose-stack render-type
    (reify SubmitNodeCollector$CustomGeometryRenderer
      (render [_ pose consumer]
        (render-fn pose consumer)))))

(defn extract-level-plan!
  "Build the immutable render plan during ExtractLevelRenderStateEvent.
   World queries and player-speed synchronization happen only in extraction."
  [{:keys [^LocalPlayer player camera-pos tick]}]
  (let [owner (client-session/current-local-player-owner)
        plan (when (power-runtime/client-level-effects-active?)
               (power-runtime/client-build-level-effect-plan
                 camera-pos
                 (hand-center-pos player)
                 tick
                 (make-nearby-block-query-fn player)))]
    (when owner
      (apply-local-walk-speed-from-plan! owner player plan))
    {:plan plan
     :camera-pos camera-pos}))

(defn render-level-plan!
  "Submit an extracted level-effect plan to 26.2's collector.
   RenderType owns blend/depth/texture/pipeline state for every callback."
  [{:keys [plan camera-pos
           ^PoseStack pose-stack
           ^SubmitNodeCollector submit-node-collector]}]
  (when (seq (:ops plan))
    (let [{:keys [lines quads plasma]} (sort-ops (:ops plan))]
      (.pushPose pose-stack)
      (try
        (.translate pose-stack
                    (double (- (:x camera-pos)))
                    (double (- (:y camera-pos)))
                    (double (- (:z camera-pos))))
        (when (seq lines)
          (submit-custom-geometry!
            submit-node-collector pose-stack (RenderTypes/linesTranslucent)
            (fn [pose consumer]
              (doseq [op lines]
                (emit-line! consumer pose op)))))
        (doseq [[texture texture-ops] quads]
          (when-let [^Identifier identifier
                     (try
                       (Identifier/tryParse (str texture))
                       (catch Exception _ nil))]
            (submit-custom-geometry!
              submit-node-collector pose-stack
              (RenderTypes/entityTranslucent identifier)
              (fn [pose consumer]
                (doseq [op texture-ops]
                  (emit-quad! consumer pose op))))))
        (when (seq plasma)
          (submit-custom-geometry!
            submit-node-collector pose-stack (RenderTypes/lightning)
            (fn [pose consumer]
              (doseq [op plasma]
                (emit-plasma-op! consumer pose camera-pos op)))))
        (finally
          (.popPose pose-stack)))))
  plan)
