(ns cn.li.ac.ability.effect.potion
  (:require [cn.li.ac.ability.effect :as effect]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(defn- apply-potion!
  [evt {:keys [target effect-id ticks amplifier]}]
  (when (and potion-effects/*potion-effects* target)
    (let [uuid (or (when (map? target) (:uuid target))
                   (get evt target))]
      (when uuid
        (potion-effects/apply-potion-effect! potion-effects/*potion-effects*
                                             uuid effect-id (int ticks) (int amplifier)))))
  evt)

(effect/defop :potion
  [evt {:keys [target effect-id ticks amplifier]}]
  (apply-potion! evt {:target target :effect-id effect-id :ticks ticks :amplifier amplifier}))

(effect/defop :potion-roll
  [evt {:keys [chance] :as params}]
  (if (< (rand) (double (or chance 1.0)))
    (apply-potion! evt (dissoc params :chance))
    evt))
