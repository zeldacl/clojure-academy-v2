(ns cn.li.ac.content.ability.shared.vec-reflection-interaction
  "Shared vec-reflection beam interaction for railgun / meltdowner.

  Matches original's ctx.attackReflect/ReflectEvent: reflection triggers
  unconditionally whenever the target's VecReflection toggle is active, with
  no CP check and no CP cost. attackReflect() cancels the event and skips the
  normal attack() call entirely, bypassing the LivingHurtEvent pipeline — so
  VecReflectionContext's own CP-consuming handleAttack/consumeDamage (which
  only fires from onLivingAttack/onLivingHurt) never runs for these
  beam-type reflects.

  No Minecraft imports."
  (:require [cn.li.ac.ability.util.toggle :as toggle]))

(defn build-reflection-callbacks
  "Build :reflect-can-fn / :reflect-shot-fn for effect.beam/execute-beam!

  Options:
    :ctx-id            context id for FX
    :reflect-shot-fn   (fn [ctx-id reflector-player-uuid] -> truthy when hit)"
  [{:keys [ctx-id reflect-shot-fn]}]
  {:reflect-can-fn
   (fn [target-player-uuid _incoming-damage]
     (boolean (toggle/toggle-active-for-player? target-player-uuid :vec-reflection)))

   :reflect-shot-fn
   (fn [target-player-uuid _incoming-damage]
     (reflect-shot-fn ctx-id target-player-uuid))})
