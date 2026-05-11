(ns cn.li.fabric1201.entity.effect-hooks
  "Fabric thin wrapper delegating scripted effect hook registration to shared mc1201 implementation."
  (:require [cn.li.mc1201.entity.effect-hooks :as shared]))

(def ^:private impl-key->hook-class
  {:intensify-arcs "cn.li.mc1201.entity.hook.effect.IntensifyArcsEffectHook"
   :owner-offset "cn.li.mc1201.entity.hook.effect.OwnerOffsetEffectHook"
   :generic-arc "cn.li.mc1201.entity.hook.effect.GenericArcEffectHook"
   :md-ball "cn.li.mc1201.entity.hook.effect.MdBallEffectHook"
   :noop "cn.li.mc1201.entity.hook.effect.NoopEffectHook"
   :coin-throwing "cn.li.mc1201.entity.hook.effect.CoinThrowingEffectHook"})

(defn register-all-effect-hooks!
  []
  (shared/register-all-effect-hooks!))
