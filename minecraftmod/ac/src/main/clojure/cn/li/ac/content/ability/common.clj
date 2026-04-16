(ns cn.li.ac.content.ability.common
  "Shared helpers for skill content implementations.

  This namespace centralizes repetitive per-skill helpers so concrete skills
  can focus on domain behavior."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]))

(defn lerp
  [a b t]
  (+ (double a) (* (- (double b) (double a)) (double t))))

(defn get-skill-exp
  [player-id skill-id]
  (double (or (get-in (ps/get-player-state player-id)
                      [:ability-data :skills skill-id :exp])
              0.0)))

(defn add-skill-exp!
  ([player-id skill-id amount]
   (add-skill-exp! player-id skill-id amount 1.0))
  ([player-id skill-id amount exp-rate]
   (skill-effects/add-skill-exp! player-id skill-id (double amount) (double exp-rate))))

(defn set-main-cooldown!
  [player-id skill-id cooldown-ticks]
  (skill-effects/set-main-cooldown! player-id skill-id (max 1 (int cooldown-ticks))))
