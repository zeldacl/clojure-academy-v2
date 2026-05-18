(ns cn.li.mc1201.entity.effect-hooks
  "Shared registration for scripted effect hook strategies."
  (:require [cn.li.mcmod.entity.hook-catalog :as hook-catalog]
            [cn.li.mc1201.entity.hook-registry-core :as hook-core])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

(defonce ^:private effect-hooks-installed? (atom false))

(def ^:private impl-key->hook-class
  {:intensify-arcs "cn.li.mc1201.entity.hook.effect.IntensifyArcsEffectHook"
   :owner-offset "cn.li.mc1201.entity.hook.effect.OwnerOffsetEffectHook"
   :generic-arc "cn.li.mc1201.entity.hook.effect.GenericArcEffectHook"
   :md-ball "cn.li.mc1201.entity.hook.effect.MdBallEffectHook"
   :noop "cn.li.mc1201.entity.hook.effect.NoopEffectHook"
   :coin-throwing "cn.li.mc1201.entity.hook.effect.CoinThrowingEffectHook"})

(defn- collect-effect-hook-entries
  []
  (hook-core/collect-hook-entries
   {:entity-kind :scripted-effect
    :property-key :effect
    :label "scripted-effect"
    :resolve-hook-class (fn [{:keys [hook-props hook-id]}]
                          (let [impl-key (or (some-> (:hook-impl-key hook-props) hook-core/normalize-impl-key)
                                             (hook-catalog/effect-impl-key hook-id))]
                            {:hook-impl-key impl-key
                             :hook-class (or (some-> (:hook-class hook-props) str)
                                             (get impl-key->hook-class impl-key))}))}))

(defn- resolve-hook-conflicts
  [hook-entries]
  (hook-core/resolve-hook-conflicts "scripted-effect" hook-entries))

(defn register-all-effect-hooks!
  []
  (hook-core/register-hook-classes!
   {:installed?-atom effect-hooks-installed?
    :entries (-> (collect-effect-hook-entries)
                 (resolve-hook-conflicts))
    :register-fn ScriptedEntitySpecAccess/registerScriptedEffectHookClass
    :success-label "Registered scripted effect hook"}))
