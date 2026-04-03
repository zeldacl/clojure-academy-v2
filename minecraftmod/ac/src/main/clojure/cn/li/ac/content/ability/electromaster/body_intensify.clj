(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill - charge to apply temporary buffs.

  Mechanics:
  - Hold key to charge (10-40 ticks, up to 100 max)
  - Release to apply buffs based on charge duration
  - Buffs: Speed III, Jump Boost II, Regeneration II, Strength II, Resistance II, Hunger III
  - Buff amplifiers scale with charge duration
  - Cooldown: 600-900 ticks (30-45 seconds)
  - Energy: 120-200 initial overload + 15-20 per tick

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.util.scaling :as scaling]
            [cn.li.ac.ability.util.charge :as charge]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

(defn- get-skill-exp [player-id]
  (when-let [state (ps/get-player-state player-id)]
    (get-in state [:ability-data :skills :body-intensify :exp] 0.0)))

(defn body-intensify-on-key-down
  "Initialize charge state when key pressed."
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (get-skill-exp player-id)
          ;; Charge timing
          min-ticks 10
          max-ticks 100
          optimal-ticks 40

          charge-state (charge/init-charge-state min-ticks max-ticks optimal-ticks)]

      ;; Store charge state in context
      (ctx/update-context! ctx-id assoc :skill-state {:charge charge-state})

      (log/debug "BodyIntensify charge started"))
    (catch Exception e
      (log/warn "BodyIntensify key-down failed:" (ex-message e)))))

(defn body-intensify-on-key-tick
  "Update charge progress each tick."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            charge-state (:charge skill-state)

            ;; Update charge progress
            new-charge-state (charge/update-charge-progress charge-state)]

        ;; Update context with new charge state
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge] new-charge-state)

        ;; Grant small experience during charge
        (when-let [state (ps/get-player-state player-id)]
          (let [{:keys [data events]} (learning/add-skill-exp
                                       (:ability-data state)
                                       player-id
                                       :body-intensify
                                       0.0001
                                       1.0)]
            (ps/update-ability-data! player-id (constantly data))
            (doseq [e events]
              (ability-evt/fire-ability-event! e))))))
    (catch Exception e
      (log/warn "BodyIntensify key-tick failed:" (ex-message e)))))

(defn body-intensify-on-key-up
  "Apply buffs when key released."
  [{:keys [player-id ctx-id]}]
  (try
    (when-let [ctx (ctx/get-context ctx-id)]
      (let [skill-state (:skill-state ctx)
            charge-state (:charge skill-state)
            exp (get-skill-exp player-id)]

        ;; Check if minimum charge reached
        (if-not (charge/is-charge-complete? charge-state)
          (log/debug "BodyIntensify: Insufficient charge")

          (let [;; Calculate charge duration
                charge-ticks (:charge-ticks charge-state)
                max-ticks (:max-ticks charge-state)
                charge-ratio (min 1.0 (/ (double charge-ticks) (double max-ticks)))

                ;; Calculate buff duration and amplifiers based on charge
                ;; Base duration: 200-600 ticks (10-30 seconds)
                base-duration (int (+ 200 (* 400 charge-ratio)))
                ;; Duration scales with experience
                duration (int (scaling/scale-duration base-duration (int (* base-duration 1.5)) exp))

                ;; Amplifiers scale with charge (0-2 for most buffs)
                amplifier (int (* 2 charge-ratio))]

            ;; Apply buffs
            (when potion-effects/*potion-effects*
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :speed duration (min 2 amplifier))
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :jump-boost duration (min 1 amplifier))
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :regeneration duration (min 1 amplifier))
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :strength duration (min 1 amplifier))
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :resistance duration (min 1 amplifier))
              ;; Hunger as drawback
              (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                                   player-id :hunger duration (min 2 amplifier)))

            ;; Grant experience based on charge quality
            (when-let [state (ps/get-player-state player-id)]
              (let [exp-gain (* 0.02 charge-ratio)
                    {:keys [data events]} (learning/add-skill-exp
                                           (:ability-data state)
                                           player-id
                                           :body-intensify
                                           exp-gain
                                           1.0)]
                (ps/update-ability-data! player-id (constantly data))
                (doseq [e events]
                  (ability-evt/fire-ability-event! e))))

            (log/debug "BodyIntensify executed: duration" duration
                       "amplifier" amplifier "charge" (int (* charge-ratio 100)) "%")))))
    (catch Exception e
      (log/warn "BodyIntensify key-up failed:" (ex-message e)))))

(defn body-intensify-on-key-abort
  "Clean up charge state on abort."
  [{:keys [ctx-id]}]
  (try
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "BodyIntensify charge aborted")
    (catch Exception e
      (log/warn "BodyIntensify key-abort failed:" (ex-message e)))))
