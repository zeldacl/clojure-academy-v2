(ns cn.li.ac.ability.effect.geom
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.effect :as effect]
            [cn.li.mcmod.platform.raycast :as raycast]))

(defn eye-pos
  [player-id]
  (let [pos (get-in (ps/get-player-state player-id) [:position])]
    {:x (double (or (:x pos) 0.0))
     :y (+ (double (or (:y pos) 64.0)) 1.62)
     :z (double (or (:z pos) 0.0))}))

(effect/defop :aim-raycast
  [evt {:keys [range]}]
  (let [player-id (:player-id evt)
        world-id (or (get-in (ps/get-player-state player-id) [:position :world-id])
                     "minecraft:overworld")
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
