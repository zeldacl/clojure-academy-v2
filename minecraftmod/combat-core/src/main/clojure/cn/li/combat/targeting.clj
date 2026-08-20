(ns cn.li.combat.targeting
  "Bounded, platform-neutral target marching helpers.

   The marcher deliberately knows nothing about Minecraft, entities, or a
   skill.  The host supplies one collision predicate and receives neutral
   coordinates plus an availability fact.  Coordinates are kept as scalar
   numbers at the boundary so callers can adapt them to their own transport
   without allocating geometry objects in the hot loop.")

(set! *warn-on-reflection* true)

(def ^:const default-clearance-steps 4)

(declare xyz)

(defn- normalize-direction
  [x y z]
  (let [length (Math/sqrt (+ (* x x) (* y y) (* z z)))]
    (if (< length 1.0e-9)
      [0.0 0.0 1.0]
      [(/ x length) (/ y length) (/ z length)])))

(defn movement-direction
  "Resolve a neutral forward/back/strafe direction from the caster look.

   Strafes are normalized independently so their length does not collapse
   when the caster looks up or down.  The keyword is an input fact, never a
   skill identifier, and unknown values safely fall back to forward."
  [look direction]
  (let [[fx fy fz] (normalize-direction
                    (double (or (:x (when (map? look) look)) 0.0))
                    (double (or (:y (when (map? look) look)) 0.0))
                    (double (or (:z (when (map? look) look)) 1.0)))]
    (case direction
      :back [(- fx) (- fy) (- fz)]
      :left (normalize-direction fz 0.0 (- fx))
      :right (normalize-direction (- fz) 0.0 fx)
      [fx fy fz])))

(defn directional-destination
  "Resolve a directional blink from feet `origin` toward `look`.

   `raycast` receives feet origin, a normalized feet-to-end direction, and
   the exact segment length.  It returns a neutral hit map or nil.  The
   policy matches the platform-independent six-face landing rules used by
   directional movement abilities; `head-blocked?` is consulted only for
   horizontal block faces."
  [{:keys [origin look eye-y direction distance raycast head-blocked?
           entity-eye-height]}]
  (let [[ox oy oz] (xyz origin)
        eye-y (double (or eye-y (+ (double oy) 1.62)))
        distance (max 0.0 (min 128.0 (double (or distance 0.0))))
        [dx dy dz] (movement-direction look direction)
        end-x (+ (double ox) (* distance dx))
        end-y (+ eye-y (* distance dy))
        end-z (+ (double oz) (* distance dz))
        ray-dx (- end-x (double ox))
        ray-dy (- end-y (double oy))
        ray-dz (- end-z (double oz))
        ray-distance (Math/sqrt (+ (* ray-dx ray-dx)
                                   (* ray-dy ray-dy)
                                   (* ray-dz ray-dz)))
        [rdx rdy rdz] (normalize-direction ray-dx ray-dy ray-dz)
        hit (when (ifn? raycast)
              (raycast (double ox) (double oy) (double oz)
                       rdx rdy rdz ray-distance))
        hit-x (double (or (:hit-x hit) (:x hit) end-x))
        hit-y (double (or (:hit-y hit) (:y hit) end-y))
        hit-z (double (or (:hit-z hit) (:z hit) end-z))
        destination
        (if (nil? hit)
          {:x end-x :y end-y :z end-z}
          (if (= :entity (:hit-type hit))
            {:x hit-x
             :y (+ hit-y (double (or (:eye-height hit)
                                     entity-eye-height
                                     1.6)))
             :z hit-z}
            (let [resolved
                  (case (:face hit)
                    :down  {:x hit-x :y (- hit-y 1.0) :z hit-z}
                    :up    {:x hit-x :y (+ hit-y 1.8) :z hit-z}
                    :north {:x hit-x :y (+ hit-y 1.7) :z (- hit-z 0.6)}
                    :south {:x hit-x :y (+ hit-y 1.7) :z (+ hit-z 0.6)}
                    :west  {:x (- hit-x 0.6) :y (+ hit-y 1.7) :z hit-z}
                    :east  {:x (+ hit-x 0.6) :y (+ hit-y 1.7) :z hit-z}
                    {:x hit-x :y hit-y :z hit-z})]
              (if (and (#{:north :south :west :east} (:face hit))
                       (ifn? head-blocked?)
                       (head-blocked? (:x resolved) (:y resolved) (:z resolved)))
                (update resolved :y - 1.25)
                resolved))))]
    {:position destination
     :from {:x (double ox) :y (double oy) :z (double oz)}
     :distance (Math/sqrt (+ (let [dx (- (double (:x destination)) (double ox))] (* dx dx))
                             (let [dy (- (double (:y destination)) (double oy))] (* dy dy))
                             (let [dz (- (double (:z destination)) (double oz))] (* dz dz))))
     :hit? (some? hit)
     :valid? true
     :direction direction}))

(defn- xyz
  [value]
  (if (and (map? value) (vector? (:vec3 value)))
    (:vec3 value)
    [(double (or (when (map? value) (:x value)) 0.0))
     (double (or (when (map? value) (:y value)) 0.0))
     (double (or (when (map? value) (:z value)) 0.0))]))

(defn- place?
  "Return true when a two-block-tall entity can occupy the point.

   Java's `(int)` truncates toward zero, including for negative coordinates;
   retaining that behavior is important because the original marcher used the
   same conversion on both server and client." 
  [collidable? x y z]
  (let [ix (int (double x))
        iy (int (double y))
        iz (int (double z))]
    (and (not (boolean (collidable? ix iy iz)))
         (not (boolean (collidable? ix (inc iy) iz))))))

(defn march-through-collision
  "March `distance` along `direction` until a wall is crossed and the first
   clear segment after it has been found.

   The result is always a neutral map when inputs are usable.  `:available?`
   is false only when the bounded march ends while still inside the wall.  A
   collision predicate must accept integer x/y/z coordinates and return a
   truthy value for a collidable block." 
  [origin direction distance step clearance-steps collidable?]
  (when (ifn? collidable?)
    (let [origin* (xyz origin)
          direction* (xyz direction)
          ox (double (nth origin* 0))
          oy (double (nth origin* 1))
          oz (double (nth origin* 2))
          dx (double (nth direction* 0))
          dy (double (nth direction* 1))
          dz (double (nth direction* 2))
          distance (max 0.0 (min 128.0 (double (or distance 0.0))))
          step (max 1.0e-6 (min 16.0 (double (or step 0.8))))
          clearance-steps (max 0 (long (or clearance-steps default-clearance-steps)))]
      (loop [stage 0
             clear-count 0
             cx ox
             cy oy
             cz oz
             travelled 0.0]
        (if (> travelled distance)
          {:position {:x cx :y cy :z cz}
           :distance travelled
           :march-distance travelled
           :available? (not= stage 1)
           :valid? (not= stage 1)}
          (let [place? (place? collidable? cx cy cz)
                nx (+ cx (* step dx))
                ny (+ cy (* step dy))
                nz (+ cz (* step dz))
                next-travelled (+ travelled step)]
            (cond
              (and (= stage 0) (not place?))
              (recur 1 clear-count nx ny nz next-travelled)

              (and (= stage 1) place?)
              (recur 2 0 nx ny nz next-travelled)

              (= stage 2)
              (if (or (not place?) (>= clear-count clearance-steps))
                {:position {:x cx :y cy :z cz}
                 :distance travelled
                 :march-distance travelled
                 :available? true
                 :valid? true}
                (recur 2 (inc clear-count) nx ny nz next-travelled))

              :else
              (recur stage clear-count nx ny nz next-travelled))))))))
