(ns cn.li.ac.ability.server.effect.geom
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.util.math.vec3 :as vec3]
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
;; 3-D vector math re-exported from shared vec3 helpers
;; ---------------------------------------------------------------------------

(def v+ vec3/v+)
(def v- vec3/v-)
(def v* vec3/v*)
(def vdot vec3/vdot)
(def vlen vec3/vlen)
(def vnorm vec3/vnorm)
(def vcross vec3/vcross)
(def orthonormal-basis vec3/orthonormal-basis)
(def vdist vec3/vdist)
(def vdist-sq vec3/vdist-sq)

(defn floor-int
  [value]
  (int (Math/floor (double value))))

(def rotate-around-axis vec3/rotate-around-axis)

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
