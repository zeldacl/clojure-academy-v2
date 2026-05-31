(ns cn.li.ac.ability.effects.potion
  (:require [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- apply-potion!
  [evt {:keys [target effect-id ticks amplifier]}]
  (when (and potion-effects/*potion-effects* target)
    (let [uuid (or (when (map? target) (:uuid target))
                   (get evt target))]
      (when uuid
        (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                             uuid effect-id (int ticks) (int amplifier)))))
  evt)

(defn execute-potion!
  [evt {:keys [target effect-id ticks amplifier]}]
  (apply-potion! evt {:target target :effect-id effect-id :ticks ticks :amplifier amplifier}))

(defn execute-potion-roll!
  [evt {:keys [chance] :as params}]
  (if (< (rand) (double (or chance 1.0)))
    (apply-potion! evt (dissoc params :chance))
    evt))


