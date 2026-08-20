(ns cn.li.combat.targeting
  "Bounded, platform-neutral target marching helpers.

   The marcher deliberately knows nothing about Minecraft, entities, or a
   skill.  The host supplies one collision predicate and receives neutral
   coordinates plus an availability fact.  Coordinates are kept as scalar
   numbers at the boundary so callers can adapt them to their own transport
   without allocating geometry objects in the hot loop.")

(set! *warn-on-reflection* true)

(def ^:const default-clearance-steps 4)

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
