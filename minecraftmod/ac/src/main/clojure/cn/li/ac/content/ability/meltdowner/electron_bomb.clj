(ns cn.li.ac.content.ability.meltdowner.electron-bomb
  "ElectronBomb skill - delayed beam shot from meltdowner energy ball.

  Pattern: :instant (key press fires the bomb)
  Cost: CP lerp(250, 180, exp), overload lerp(120, 90, exp)
  Damage: lerp(6, 12, exp) at target, fired as beam after 20-tick delay
  Cooldown: lerp(20, 10, exp) ticks
  Exp: +0.003 per hit

  Implementation note: the 'ball' delay is simulated by firing the beam
  immediately with a short visual delay communicated to the FX layer.

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
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :electron-bomb :exp]
                  0.0)))

;; ---------------------------------------------------------------------------
;; Action
;; ---------------------------------------------------------------------------

(defn- perform-electron-bomb! [{:keys [player-id ctx-id]}]
  (try
    (let [exp      (skill-exp player-id)
          damage   (bal/lerp 6.0 12.0 exp)
          world-id (geom/world-id-of player-id)
          eye      (geom/eye-pos player-id)
          look-vec (when raycast/*raycast*
                     (raycast/get-player-look-vector raycast/*raycast* player-id))]
      (when look-vec
        ;; Send FX first (ball spawn + delay animation)
        (ctx/ctx-send-to-client! ctx-id :electron-bomb/fx-spawn
                                 {:x (:x eye) :y (:y eye) :z (:z eye)
                                  :dx (:x look-vec) :dy (:y look-vec) :dz (:z look-vec)})
        ;; Fire beam (represents ball impact after delay)
        (let [result (effect/run-op!
                       {:player-id  player-id
                        :ctx-id     ctx-id
                        :world-id   world-id
                        :eye-pos    eye
                        :look-dir   look-vec}
                       [:beam {:radius          0.3
                               :query-radius    20.0
                               :step            0.8
                               :max-distance    30.0
                               :visual-distance 28.0
                               :damage          damage
                               :damage-type     :magic
                               :break-blocks?   false
                               :block-energy    0.0
                               :fx-topic        :electron-bomb/fx-beam}])]
          (when (get-in result [:beam-result :performed?])
            (skill-effects/add-skill-exp! player-id :electron-bomb 0.003))
          (log/debug "ElectronBomb: fired, hit?" (boolean (get-in result [:beam-result :performed?]))))))
    (catch Exception e
      (log/warn "ElectronBomb perform! failed:" (ex-message e)))))

(defn electron-bomb-perform!
  [{:keys [player-id ctx-id] :as evt}]
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
                                      (bal/lerp 250.0 180.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 120.0 90.0 (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (int (bal/lerp 20.0 10.0 (skill-exp player-id))))
  :actions        {:perform! electron-bomb-perform!}
  :prerequisites  [])
