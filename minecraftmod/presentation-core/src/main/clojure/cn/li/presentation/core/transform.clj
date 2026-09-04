(ns cn.li.presentation.core.transform
  "Affine view transform for :transform nodes (panel-scale + tilt).

   Layout stays in logical pixels. Paint remaps CmdBuf geom into screen
   space around a pivot; hit-testing inverts the same mapping so HitKernel
   still sees logical coordinates. True projective FOV foreshortening is
   intentionally out of scope — GuiGraphics fill/blit/scissor are AABB."
  (:import [cn.li.presentation.core.engine CmdBuf NodeTable NodeFlags]))

(def ^:private default-panel-scale 1.0)
(def ^:private default-tilt-degrees 0.0)

(defn normalize
  "Canonical transform map from a style-table entry or EDN :transform style."
  [m]
  (when (map? m)
    (let [ps (or (:panel-scale m) (:panel_scale m) default-panel-scale)
          tilt (or (:tilt-degrees m) (:tilt_degrees m) default-tilt-degrees)
          fov (:fov m)
          near (:near m)
          far (:far m)]
      (cond-> {:panel-scale (double ps)
               :tilt-degrees (double tilt)}
        (number? fov) (assoc :fov (double fov))
        (number? near) (assoc :near (double near))
        (number? far) (assoc :far (double far))))))

(defn from-style-entry
  "Read transform params from a style-table value ({:transform {...}} or bare map)."
  [entry]
  (cond
    (and (map? entry) (map? (:transform entry))) (normalize (:transform entry))
    (map? entry) (normalize entry)
    :else nil))

(defn find-in-table
  "First HAS_TRANSFORM node's style entry on the NodeTable, or nil."
  [^NodeTable table]
  (when (some? table)
    (let [n (int (.-n table))
          ^ints flags (.-flags table)
          ^ints style (.-style table)
          ^objects style-table (.-styleTable table)]
      (loop [i (int 0)]
        (when (< i n)
          (if (zero? (bit-and (aget flags i) NodeFlags/HAS_TRANSFORM))
            (recur (unchecked-inc-int i))
            (let [si (aget style i)]
              (when (and (>= si 0) (< si (alength style-table)))
                (from-style-entry (aget style-table si))))))))))

(defn- pivot
  "Center of the content rect used as affine pivot."
  [content-rect]
  (let [x (float (:x content-rect 0.0))
        y (float (:y content-rect 0.0))
        w (float (:width content-rect 0.0))
        h (float (:height content-rect 0.0))]
    [(+ x (* w 0.5)) (+ y (* h 0.5))]))

(defn forward-point
  "Map logical (lx,ly) → screen around pivot."
  [xf content-rect lx ly]
  (let [scale (double (or (:panel-scale xf) 1.0))
        tilt-deg (double (or (:tilt-degrees xf) 0.0))
        [cx cy] (pivot content-rect)
        dx (- (double lx) cx)
        dy (- (double ly) cy)
        rad (Math/toRadians tilt-deg)
        cos (Math/cos rad)
        sin (Math/sin rad)
        rx (* scale (+ (* dx cos) (* dy (- sin))))
        ry (* scale (+ (* dx sin) (* dy cos)))]
    [(+ cx rx) (+ cy ry)]))

(defn inverse-point
  "Map screen (sx,sy) → logical around pivot (inverse of forward-point)."
  [xf content-rect sx sy]
  (let [scale (double (or (:panel-scale xf) 1.0))
        tilt-deg (double (or (:tilt-degrees xf) 0.0))
        [cx cy] (pivot content-rect)
        inv (if (zero? scale) 1.0 (/ 1.0 scale))
        dx (* (- (double sx) cx) inv)
        dy (* (- (double sy) cy) inv)
        rad (Math/toRadians (- tilt-deg))
        cos (Math/cos rad)
        sin (Math/sin rad)
        rx (+ (* dx cos) (* dy (- sin)))
        ry (+ (* dx sin) (* dy cos))]
    [(+ cx rx) (+ cy ry)]))

(defn apply-to-cmdbuf!
  "Rewrite CmdBuf geom in-place from logical → screen. Clears clip indices
   (axis-aligned scissors are wrong under tilt/scale)."
  [^CmdBuf buf xf content-rect]
  (when (and (some? buf) (map? xf) (map? content-rect))
    (cn.li.presentation.core.engine.TransformGeom/applyAffine
     buf
     (float (or (:panel-scale xf) 1.0))
     (float (or (:tilt-degrees xf) 0.0))
     (float (:x content-rect 0.0))
     (float (:y content-rect 0.0))
     (float (:width content-rect 0.0))
     (float (:height content-rect 0.0))))
  buf)
