(ns cn.li.ac.content.ability.teleporter.threatening-teleport

  "ThreateningTeleport skill - execute throw/damage sequence at aim position.



  Pattern: :release-cast (aim while holding key, execute on key up)

  Range: lerp(8, 15, exp)

  Damage: lerp(3, 6, exp)

  CP cost (up-stage): lerp(35, 100, exp)

  Overload cost (up-stage): lerp(18, 10, exp)

  Cooldown: lerp(30, 15, exp) ticks

  Exp: 0.003 * (hit? 1.0 : 0.2)



  No Minecraft imports."

  (:require [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]

            [cn.li.ac.ability.fx :as fx]

            [cn.li.ac.achievement.dispatcher :as ach-dispatcher]

            [cn.li.ac.ability.service.context-dispatcher :as ctx]

            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]

                        [cn.li.ac.ability.service.skill-effects :as skill-effects]

            [cn.li.ac.ability.effects.geom :as geom]
            [cn.li.ac.config.modid :as modid]

            [cn.li.ac.content.ability.teleporter.tp-skill-helper :as helper]
            [cn.li.ac.content.ability.teleporter.release-cast-base :as release-cast]

                        [cn.li.mcmod.platform.entity :as entity]

            [cn.li.mcmod.platform.raycast :as raycast]

            [cn.li.mcmod.util.log :as log]))



;; ---------------------------------------------------------------------------

;; Constants

;; ---------------------------------------------------------------------------



(def-skill-config-ops :threatening-teleport)

(def ^:private threatening-teleport-skill-id :threatening-teleport)

(def ^:private default-eye-height 1.62)



;; ---------------------------------------------------------------------------

;; Helpers

;; ---------------------------------------------------------------------------



(defn- has-main-hand-item?

  [player]

  (and player

       (pos? (int (or (entity/player-get-main-hand-item-count player) 0)))))



(defn- settle-main-hand-item!

  [player trace drop?]

  (if (nil? player)

    false

    (let [creative? (boolean (entity/player-creative? player))
          x (double (:drop-x trace))
          y (double (:drop-y trace))
          z (double (:drop-z trace))]
      (cond
        (and drop? creative?)
        (entity/player-spawn-main-hand-item-copy-at! player 1 x y z)

        drop?
        (entity/player-drop-main-hand-item-at! player 1 x y z)

        creative?
        true

        :else
        (entity/player-consume-main-hand-item! player 1)))))

(defn- needle-in-main-hand?
  [player]
  (= (modid/namespaced-path "needle")
     (some-> player entity/player-get-main-hand-item-id str)))



(defn- trace-result

  [player-id range]

  (let [player-pos (helper/player-position player-id)

        look-vec (helper/player-look-vec player-id)

        world-id (geom/world-id-of player-id)]

    (when (and player-pos look-vec (raycast/available?))

      (let [sx (double (:x player-pos))

            sy (+ (double (:y player-pos)) default-eye-height)

            sz (double (:z player-pos))

            dx (double (:x look-vec))

            dy (double (:y look-vec))

            dz (double (:z look-vec))

            hit (raycast/raycast-combined

                                          world-id

                                          sx sy sz dx dy dz

                                          (double range))]

        (if hit

          (let [hit-x (double (or (:hit-x hit) (:x hit) sx))

                hit-y (double (or (:hit-y hit) (:y hit) sy))

                hit-z (double (or (:hit-z hit) (:z hit) sz))

                target-uuid (or (:entity-uuid hit) (:uuid hit))

                attacked? (= :entity (:hit-type hit))
                drop-x (if attacked?
                         (double (or (:x hit) hit-x))
                         hit-x)
                drop-y (if attacked?
                         (+ (double (or (:y hit) hit-y))
                            (double (or (:height hit) 0.0)))
                         hit-y)
                drop-z (if attacked?
                         (double (or (:z hit) hit-z))
                         hit-z)]

            {:world-id world-id

             :start-x sx :start-y sy :start-z sz

             :drop-x drop-x :drop-y drop-y :drop-z drop-z

             :attacked? attacked?

             :target-uuid target-uuid

             :distance (double (or (:distance hit)

                                   (Math/sqrt (+ (* (- hit-x sx) (- hit-x sx))

                                                 (* (- hit-y sy) (- hit-y sy))

                                                 (* (- hit-z sz) (- hit-z sz))))))})

          (let [mx (+ sx (* dx (double range)))

                my (+ sy (* dy (double range)))

                mz (+ sz (* dz (double range)))]

            {:world-id world-id

             :start-x sx :start-y sy :start-z sz

             :drop-x mx :drop-y my :drop-z mz

             :attacked? false

             :target-uuid nil

             :distance (double range)}))))))



(defn- exp-gain

  [attacked?]

  (* (cfg-double :progression.exp-base)

     (if attacked?

       (cfg-double :progression.exp-hit-factor)

       (cfg-double :progression.exp-miss-factor))))



(defn- should-drop?

  [attacked?]

  (< (rand)

     (if attacked?

       (cfg-probability :interaction.drop-prob.hit)

       (cfg-probability :interaction.drop-prob.miss))))





(defn- threatening-tp-tick-impl!

  [ctx-id player-id _skill-id _exp _cost-ok? hold-ticks _cost-stage _player-ref]

  (let [exp (skill-exp player-id)

        range (cfg-lerp :targeting.range exp)

        trace (trace-result player-id range)]

    (ctx-skill/replace-skill-state! ctx-id {:hold-ticks (long hold-ticks)

                     :trace trace})

    (when trace

      (fx/send! ctx-id {:topic :threatening-teleport/fx-update :mode :update} nil

                {:start-x (:start-x trace)

                 :start-y (:start-y trace)

                 :start-z (:start-z trace)

                 :drop-x (:drop-x trace)

                 :drop-y (:drop-y trace)

                 :drop-z (:drop-z trace)

                 :attacked? (:attacked? trace)

                 :target-uuid (:target-uuid trace)}))))



(defn- threatening-tp-up-impl!

  [ctx-id player-id _skill-id _exp cost-ok? _hold-ticks _cost-stage player-ref]

  (try

    (let [exp (skill-exp player-id)

          base-damage (cfg-lerp :combat.damage exp)
          damage (if (needle-in-main-hand? player-ref)
                   (* base-damage (cfg-double :combat.needle-damage-multiplier))
                   base-damage)

          ctx-data (ctx-skill/get-context ctx-id)

          range (cfg-lerp :targeting.range exp)

          trace (or (get-in ctx-data [:skill-state :trace])

                    (trace-result player-id range))]

      (when (and cost-ok? trace (has-main-hand-item? player-ref))

        (let [world-id (:world-id trace)

              target-uuid (:target-uuid trace)

              attacked? (boolean (and (:attacked? trace) target-uuid))

              drop? (should-drop? attacked?)

              consumed? (settle-main-hand-item! player-ref trace drop?)]

          (when consumed?

            (let [damage-result (when attacked?

                                  (helper/deal-magic-damage!
                                    player-id
                                    threatening-teleport-skill-id
                                    world-id
                                    target-uuid
                                    damage))]

              (when (helper/crit-applied? damage-result)

                (fx/send! ctx-id {:topic :teleporter/fx-crit-hit} nil

                          {:x (:drop-x trace)

                           :y (:drop-y trace)

                           :z (:drop-z trace)

                           :crit-level (:crit-level damage-result)

                           :crit-rate (:crit-rate damage-result)

                           :message-key (:message-key damage-result)

                           :message-args (:message-args damage-result)

                           :target-uuid target-uuid

                           :skill-id threatening-teleport-skill-id}))

              (skill-effects/add-skill-exp! player-id threatening-teleport-skill-id (exp-gain attacked?))

              (let [cd (cfg-lerp-int :cooldown.ticks exp)]

                (skill-effects/set-main-cooldown! player-id threatening-teleport-skill-id cd))

              (ach-dispatcher/trigger-custom-event! player-id "teleporter.threatening_teleport")

              ;; Original's s_execute sendToClient(MSG_EXECUTE, attacked) — the
              ;; sound + particle trail in c_end run unconditionally; only the
              ;; marker cleanup is isLocal-gated (:threatening-teleport/fx-update).
              (fx/send-local-and-nearby! ctx-id {:topic :threatening-teleport/fx-perform :mode :perform} nil

                        {:start-x (:start-x trace)

                         :start-y (:start-y trace)

                         :start-z (:start-z trace)

                         :drop-x (:drop-x trace)

                         :drop-y (:drop-y trace)

                         :drop-z (:drop-z trace)

                         :attacked? attacked?

                         :dropped? drop?}))))))

    (catch Exception e

      (log/warn "ThreateningTeleport up! failed:" (ex-message e)))))



(def ^:private release-cast-ops

  (release-cast/build-ops

    {:initial-state {:hold-ticks 0 :trace nil}

     :tick! threatening-tp-tick-impl!

     :up! threatening-tp-up-impl!}))



(defn threatening-tp-down! [& args] (apply release-cast/down! release-cast-ops args))

(defn threatening-tp-tick! [& args] (apply release-cast/tick! release-cast-ops args))

(defn threatening-tp-up! [& args] (apply release-cast/up! release-cast-ops args))

(defn threatening-tp-abort! [& args] (apply release-cast/abort! release-cast-ops args))



;; ---------------------------------------------------------------------------

;; Skill registration

;; ---------------------------------------------------------------------------



(defskill threatening-teleport

  :id             :threatening-teleport

  :category-id    :teleporter

  :name-key       "ability.skill.teleporter.threatening_teleport"

  :description-key "ability.skill.teleporter.threatening_teleport.desc"

  :icon           "textures/abilities/teleporter/skills/threatening_teleport.png"

  :ui-position    [14 42]



  :ctrl-id        :threatening-teleport

  :cp-consume-speed 0.0

  :overload-consume-speed 0.0

  :pattern        :release-cast

  :cost           {:up {:cp       (fn [player-id _skill-id _exp]

                                    (cfg-lerp :cost.up.cp

                                                     (skill-exp player-id)))

                        :overload (fn [player-id _skill-id _exp]

                                    (cfg-lerp :cost.up.overload

                                                     (skill-exp player-id)))

                        :creative (fn [_player-id _skill-id _exp]

                                    false)}}

  :cooldown       {:mode :manual}

  :actions        {:down!  threatening-tp-down!

                   :tick!  threatening-tp-tick!

                   :up!    threatening-tp-up!

                   :abort! threatening-tp-abort!}

  :fx             {:start {:topic :threatening-teleport/fx-start :payload (fn [_] {})}

                   :update {:topic :threatening-teleport/fx-update :payload (fn [_] {})}

                   :end   {:topic :threatening-teleport/fx-end   :payload (fn [_] {})}}

  :prerequisites  [])



