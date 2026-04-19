(ns cn.li.ac.content.ability.meltdowner.ray-barrage
  "RayBarrage skill - rapid multi-target beam burst from fired silbarn.

  Pattern: :instant
  Cost: CP lerp(300, 220, exp), overload lerp(130, 100, exp)
  Mechanic: raycast for nearest living entity in range 20;
            fire 5 rapid beams spread in a cone at the target cluster
  Damage per beam: lerp(4, 10, exp)
  Cooldown: lerp(40, 25, exp) ticks
  Exp: +0.003 per cast hitting something

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :ray-barrage :exp]
                  0.0)))

(defn- jitter-dir
  "Add small random jitter to a direction vector."
  [look-vec spread]
  (let [rx (* spread (- (rand 1.0) 0.5))
        ry (* spread (- (rand 1.0) 0.5))
        rz (* spread (- (rand 1.0) 0.5))
        dx (+ (double (:x look-vec)) rx)
        dy (+ (double (:y look-vec)) ry)
        dz (+ (double (:z look-vec)) rz)
        len (max 1.0e-6 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
    {:x (/ dx len) :y (/ dy len) :z (/ dz len)}))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn ray-barrage-perform!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp      (skill-exp player-id)
          damage   (bal/lerp 4.0 10.0 exp)
          world-id (geom/world-id-of player-id)
          eye      (geom/eye-pos player-id)
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        ;; Fire 5 beams in a spread cone
        (let [hit-count (atom 0)]
          (dotimes [_i 5]
            (let [dir (jitter-dir look-vec 0.18)
                  result (effect/run-op!
                           {:player-id  player-id
                            :ctx-id     ctx-id
                            :world-id   world-id
                            :eye-pos    eye
                            :look-dir   {:x (:x dir) :y (:y dir) :z (:z dir)}}
                           [:beam {:radius          0.3
                                   :query-radius    20.0
                                   :step            0.8
                                   :max-distance    22.0
                                   :visual-distance 20.0
                                   :damage          damage
                                   :damage-type     :magic
                                   :break-blocks?   false
                                   :block-energy    0.0
                                   :fx-topic        :ray-barrage/fx-beam}])]
              (when (get-in result [:beam-result :performed?])
                (swap! hit-count inc))))
          (when (pos? @hit-count)
            (skill-effects/add-skill-exp! player-id :ray-barrage 0.003)
            (log/debug "RayBarrage: hit" @hit-count "beams")))))
    (catch Exception e
      (log/warn "RayBarrage perform! failed:" (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! ray-barrage
  :id             :ray-barrage
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.ray_barrage"
  :description-key "ability.skill.meltdowner.ray_barrage.desc"
  :icon           "textures/abilities/meltdowner/skills/ray_barrage.png"
  :ui-position    [60 200]
  :level          4
  :controllable?  false
  :ctrl-id        :ray-barrage
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 300.0 220.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 130.0 100.0 (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (int (bal/lerp 40.0 25.0 (skill-exp player-id))))
  :actions        {:perform! ray-barrage-perform!}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.5}])
