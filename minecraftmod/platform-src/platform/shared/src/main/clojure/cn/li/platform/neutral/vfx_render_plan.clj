(ns cn.li.platform.neutral.vfx-render-plan
  "Translate the Minecraft-free VFX draw ABI into a version geometry plan.

   VFX Core owns evaluated, typed-neutral draw operations.  The existing
   mc-* geometry modules remain responsible for Minecraft buffer state, so
   this adapter only expands line/beam/ring/quad geometry into their
   long-lived {:ops [...]} plan shape.  It imports no Minecraft class."
  (:import [cn.li.mcmod.math V3]))

(def ^:private default-color [255 255 255 255])
(def ^:private default-texture "minecraft:textures/misc/white.png")

(defn- number-or [value fallback]
  (if (number? value) (double value) (double fallback)))

(defn- v3-from
  ^V3 [value]
  (cond
    (instance? V3 value) value
    (and (map? value) (sequential? (:vec3 value)))
    (let [[x y z] (concat (:vec3 value) [0.0 0.0 0.0])]
      (V3. (number-or x 0.0) (number-or y 0.0) (number-or z 0.0)))
    (map? value)
    (V3. (number-or (or (:x value) (get value "x")) 0.0)
         (number-or (or (:y value) (get value "y")) 0.0)
         (number-or (or (:z value) (get value "z")) 0.0))
    (sequential? value)
    (let [[x y z] (concat value [0.0 0.0 0.0])]
      (V3. (number-or x 0.0) (number-or y 0.0) (number-or z 0.0)))
    :else
    (V3. 0.0 0.0 0.0)))

(defn- material-color [material]
  (let [color (or (:color material) (get material "color") default-color)
        alpha (:alpha material)]
    (if (and (number? alpha) (map? color))
      (assoc color :a (* (double (or (:a color) 1.0)) (double alpha)))
      color)))

(defn- line-op [p1 p2 color]
  {:kind :line :p1 (v3-from p1) :p2 (v3-from p2) :color color})

(defn- ring-ops [{:keys [center radius segments]} color]
  (let [center (v3-from center)
        radius (max 0.0 (number-or radius 0.0))
        segments (long (max 3 (min 256 (number-or segments 16))))]
    (mapv (fn [i]
            (let [a0 (* 2.0 Math/PI (/ i segments))
                  a1 (* 2.0 Math/PI (/ (inc i) segments))]
              (line-op
               (V3. (+ (.-x center) (* radius (Math/cos a0)))
                    (.-y center)
                    (+ (.-z center) (* radius (Math/sin a0))))
               (V3. (+ (.-x center) (* radius (Math/cos a1)))
                    (.-y center)
                    (+ (.-z center) (* radius (Math/sin a1))))
               color)))
          (range segments))))

(defn- line-ops [geometry color]
  (let [p1 (or (:p1 geometry) (:start geometry) (:from geometry))
        p2 (or (:p2 geometry) (:end geometry) (:to geometry))]
    (if (and p1 p2) [(line-op p1 p2 color)] [])))

(defn- quad-ops [geometry color material]
  (let [corners (map #(get geometry %) [:p0 :p1 :p2 :p3])]
    (if (every? some? corners)
      [{:kind :quad
        :p0 (v3-from (nth corners 0))
        :p1 (v3-from (nth corners 1))
        :p2 (v3-from (nth corners 2))
        :p3 (v3-from (nth corners 3))
        :u0 (float (number-or (:u0 geometry) 0.0))
        :u1 (float (number-or (:u1 geometry) 1.0))
        :v0 (float (number-or (:v0 geometry) 0.0))
        :v1 (float (number-or (:v1 geometry) 1.0))
        :texture (or (:texture geometry) (:texture material) default-texture)
        :color color}]
      [])))

(defn neutral-op->plan
  "Return the mc-* geometry plan for one neutral draw-batch operation.

   Unsupported typed/emitter payloads intentionally become an empty plan: the
   operation has crossed the ABI and cannot be interpreted by a version layer
   without an explicit renderer implementation.  No legacy payload is read."
  [op]
  (when (and (map? op) (= :draw-batch (:operation op)))
    (let [geometry (or (:geometry op) {})
          material (or (:material op) {})
          color (material-color material)
          primitive (:primitive op)
          ops (case primitive
                :line (case (:kind geometry)
                        :ring (ring-ops geometry color)
                        :beam (line-ops {:start (:start geometry)
                                         :end (:end geometry)} color)
                        (line-ops geometry color))
                :quad (quad-ops geometry color material)
                [])]
      {:ops ops})))
