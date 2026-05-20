(ns cn.li.ac.content.ability.meltdowner.electron-bomb
  "ElectronBomb skill - delayed beam shot from meltdowner energy ball.

  Pattern: :instant (key press fires the bomb)
  Cost: CP lerp(250, 180, exp), overload lerp(120, 90, exp)
  Damage: lerp(6, 12, exp) at target, settled near MdBall end-of-life callback
  Cooldown: lerp(20, 10, exp) ticks
  Exp: +0.003 per hit

  Implementation note: the 'ball' delay is simulated by firing the beam
  immediately with a short visual delay communicated to the FX layer.

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.server.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private mdball-entity-id "my_mod:entity_md_ball")
(def ^:private electron-bomb-skill-id :electron-bomb)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (skill-effects/skill-exp player-id electron-bomb-skill-id))

(defn- cfg-double [field-id]
  (skill-config/tunable-double electron-bomb-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double electron-bomb-skill-id field-id exp))

(defn- cfg-lerp-int [field-id exp]
  (skill-config/lerp-int electron-bomb-skill-id field-id exp))

(defn- beam-config []
  {:radius          (cfg-double :beam.radius)
   :query-radius    (cfg-double :beam.query-radius)
   :step            (cfg-double :beam.step)
   :max-distance    (cfg-double :beam.max-distance)
   :visual-distance (cfg-double :beam.visual-distance)})

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-electron-bomb! [{:keys [player-id ctx-id player]}]
  (try
    (let [exp      (skill-exp player-id)
          damage   (cfg-lerp :combat.damage exp)
          world-id (geom/world-id-of player-id)
          eye      (geom/eye-pos player-id)
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        (when player
          (entity/player-spawn-entity-by-id! player mdball-entity-id 0.0))
        ;; Send FX first (ball spawn + delay animation)
        (ctx/ctx-send-to-client! ctx-id :electron-bomb/fx-spawn
                                 {:x (:x eye) :y (:y eye) :z (:z eye)
                                  :dx (:x look-vec) :dy (:y look-vec) :dz (:z look-vec)})
        (delayed-projectiles/schedule-electron-bomb-beam!
          {:player-id player-id
           :ctx-id ctx-id
           :world-id world-id
           :eye eye
           :look-dir look-vec
           :damage damage
           :beam (beam-config)
           :exp-gain (cfg-double :progression.exp-hit)
           :delay-ticks (delayed-projectiles/mdball-near-expire-delay)})
          (log/debug "ElectronBomb: scheduled delayed beam"
                 {:delay (delayed-projectiles/mdball-near-expire-delay)
                  :player player-id})))
    (catch Exception e
      (log/warn "ElectronBomb perform! failed:" (ex-message e)))))

(defn electron-bomb-perform!
  [evt]
  (perform-electron-bomb! evt))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! electron-bomb
  :id             :electron-bomb
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.electron_bomb"
  :description-key "ability.skill.meltdowner.electron_bomb.desc"
  :icon           "textures/abilities/meltdowner/skills/electron_bomb.png"
  :ui-position    [70 120]
  :level          1
  :controllable?  false
  :ctrl-id        :electron-bomb
  :pattern        :instant
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.cp (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (cfg-lerp :cost.down.overload (skill-exp player-id)))} }
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (cfg-lerp-int :cooldown.ticks (skill-exp player-id)))
  :actions        {:perform! electron-bomb-perform!}
  :prerequisites  [])
