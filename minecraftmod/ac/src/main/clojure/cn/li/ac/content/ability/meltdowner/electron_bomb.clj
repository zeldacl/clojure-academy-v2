(ns cn.li.ac.content.ability.meltdowner.electron-bomb
  "ElectronBomb skill - delayed MdBall settlement with single-ray hit.

  Pattern: :instant (key press fires the bomb)
  Cost: CP lerp(250, 180, exp), overload lerp(120, 90, exp)
  Damage: lerp(6, 12, exp) at target, settled near MdBall end-of-life callback
  Cooldown: lerp(20, 10, exp) ticks
  Exp: +0.003 per hit

  Implementation note: the 'ball' delay is driven by delayed projectile
  settlement and the client FX receives a single-ray visual event.

  No Minecraft imports."
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.delayed-projectiles :as delayed-projectiles]
            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :electron-bomb)
(def ^:private mdball-entity-id (modid/namespaced-path "entity_md_ball"))
;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-electron-bomb! [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage player-ref]
  (try
    (let [damage   (cfg-lerp :combat.damage exp)
          world-id (geom/world-id-of player-id)
          eye      (geom/eye-pos player-id)
          look-vec (when (raycast/available?)
                     (raycast/get-player-look-vector* player-id))]
      (when look-vec
        (when player-ref
          (entity/player-spawn-entity-by-id!
            player-ref
            mdball-entity-id
            0.0))
        ;; Send spawn FX first; the delayed task owns the actual hit settlement.
        (fx/send! ctx-id {:topic :electron-bomb/fx-spawn} nil
                  {:x (:x eye) :y (:y eye) :z (:z eye)
                   :dx (:x look-vec) :dy (:y look-vec) :dz (:z look-vec)})
        (delayed-projectiles/schedule-electron-bomb-beam!
          {:player-id player-id
           :ctx-id ctx-id
           :world-id world-id
           :eye eye
           :look-dir look-vec
           :damage damage
           :exp-gain (cfg-double :progression.exp-hit)
           :delay-ticks (delayed-projectiles/mdball-near-expire-delay)})
        (log/debug "ElectronBomb: scheduled delayed beam"
                   {:delay (delayed-projectiles/mdball-near-expire-delay)
                    :player player-id})))
    (catch Exception e
      (log/warn "ElectronBomb perform! failed:" (ex-message e)))))

(defn electron-bomb-perform!
  [& args]
  (apply perform-electron-bomb! args))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill electron-bomb
  :id             :electron-bomb
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.electron_bomb"
  :description-key "ability.skill.meltdowner.electron_bomb.desc"
  :icon           "textures/abilities/meltdowner/skills/electron_bomb.png"
  :ui-position    [15 45]
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

