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
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.core :as effect]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.ac.content.ability.meltdowner.damage-helper :as md-damage]
            [cn.li.ac.ability.server.effect.beam]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private ray-barrage-skill-id :ray-barrage)

(defn- cfg-double [field-id]
  (skill-config/tunable-double ray-barrage-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int ray-barrage-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double ray-barrage-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int ray-barrage-skill-id field-id exp))

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id ray-barrage-skill-id))

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
          damage   (cfg-lerp :combat.damage exp)
          world-id (geom/world-id-of player-id)
          eye      (geom/eye-pos player-id)
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        ;; Fire 5 beams in a spread cone
        (let [hit-count (atom 0)]
          (dotimes [_i (cfg-int :beam.count)]
            (let [dir (jitter-dir look-vec (cfg-double :beam.spread))
                  result (effect/run-op!
                           {:player-id  player-id
                            :ctx-id     ctx-id
                            :world-id   world-id
                            :eye-pos    eye
                            :look-dir   {:x (:x dir) :y (:y dir) :z (:z dir)}}
                             [:beam {:radius          (cfg-double :beam.radius)
                               :query-radius    (cfg-double :beam.query-radius)
                               :step            (cfg-double :beam.step)
                               :max-distance    (cfg-double :beam.max-distance)
                               :visual-distance (cfg-double :beam.visual-distance)
                                   :damage          damage
                                   :damage-type     :magic
                                   :break-blocks?   false
                                   :block-energy    0.0
                                   :fx-topic        :ray-barrage/fx-beam}])]
              (when (get-in result [:beam-result :performed?])
                (doseq [target-id (or (get-in result [:beam-result :hit-uuids]) [])]
                  (md-damage/mark-target! player-id target-id {:ctx-id ctx-id}))
                (swap! hit-count inc))))
          (when (pos? @hit-count)
            (skill-effects/add-skill-exp! player-id ray-barrage-skill-id
                                          (cfg-double :progression.exp-hit))
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
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))} }
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (cfg-lerp-int :cooldown.ticks (skill-exp player-id)))
  :actions        {:perform! ray-barrage-perform!}
  :prerequisites  [{:skill-id :meltdowner :min-exp 0.5}])
