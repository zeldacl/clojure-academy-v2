(ns cn.li.ac.ability.effect.geom
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.effect :as effect]
            [cn.li.mcmod.platform.raycast :as raycast]))

;; ---------------------------------------------------------------------------
;; Player helpers
;; ---------------------------------------------------------------------------

(defn world-id-of
  [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn eye-pos
  [player-id]
  (let [pos (get-in (ps/get-player-state player-id) [:position])]
    {:x (double (or (:x pos) 0.0))
     :y (+ (double (or (:y pos) 64.0)) 1.62)
     :z (double (or (:z pos) 0.0))}))

;; ---------------------------------------------------------------------------
;; 3-D vector math (all operate on {:x _ :y _ :z _} maps)
;; ---------------------------------------------------------------------------

(defn v+
  [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn v-
  [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn v*
  [a scale]
  {:x (* (double (:x a)) (double scale))
   :y (* (double (:y a)) (double scale))
   :z (* (double (:z a)) (double scale))})

(defn vdot
  [a b]
  (+ (* (double (:x a)) (double (:x b)))
     (* (double (:y a)) (double (:y b)))
     (* (double (:z a)) (double (:z b)))))

(defn vlen
  [a]
  (Math/sqrt (vdot a a)))

(defn vnorm
  [a]
  (let [len (max 1.0e-6 (vlen a))]
    (v* a (/ 1.0 len))))

(defn vcross
  [a b]
  {:x (- (* (double (:y a)) (double (:z b))) (* (double (:z a)) (double (:y b))))
   :y (- (* (double (:z a)) (double (:x b))) (* (double (:x a)) (double (:z b))))
   :z (- (* (double (:x a)) (double (:y b))) (* (double (:y a)) (double (:x b))))})

(defn orthonormal-basis
  "Return [right up] orthonormal vectors perpendicular to dir."
  [dir]
  (let [up-axis (if (> (Math/abs (double (:y dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (vnorm (vcross dir up-axis))
        up    (vnorm (vcross right dir))]
    [right up]))

(defn vdist
  [a b]
  (vlen (v- a b)))

(defn vdist-sq
  [a b]
  (let [dx (- (double (:x a)) (double (:x b)))
        dy (- (double (:y a)) (double (:y b)))
        dz (- (double (:z a)) (double (:z b)))]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn floor-int
  [value]
  (int (Math/floor (double value))))

;; ---------------------------------------------------------------------------
;; Effect ops
;; ---------------------------------------------------------------------------

(effect/defop :aim-raycast
  [evt {:keys [range]}]
  (let [player-id (:player-id evt)
        world-id (world-id-of player-id)
        eye (eye-pos player-id)
        look (when raycast/*raycast*
               (raycast/get-player-look-vector raycast/*raycast* player-id))
        hit (when (and raycast/*raycast* look)
              (raycast/raycast-combined raycast/*raycast*
                                        world-id
                                        (:x eye) (:y eye) (:z eye)
                                        (double (or (:x look) 0.0))
                                        (double (or (:y look) 0.0))
                                        (double (or (:z look) 1.0))
                                        (double (or range 20.0))))]
    (assoc evt :world-id world-id :eye-pos eye :look-dir look :hit hit)))
