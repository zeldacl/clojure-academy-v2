(ns cn.li.mcbase.entity.hooks
  "Shared scripted entity hook registration (prefix must be installed first)."
  (:require [cn.li.mcbase.entity.hook-registry-core :as hook-core]))

(defn- supplier [factory]
  (reify java.util.function.Supplier
    (get [_] (factory))))

(defn install-base-hook-factories!
  []
  (hook-core/install-hook-factories!
   {:effect {"cn.li.mcbase.entity.hook.effect.OwnerOffsetEffectHook"
             (supplier #(cn.li.mcbase.entity.hook.effect.OwnerOffsetEffectHook.))
             "cn.li.mcbase.entity.hook.effect.NoopEffectHook"
             (supplier #(cn.li.mcbase.entity.hook.effect.NoopEffectHook.))}
    :ray {"cn.li.mcbase.entity.hook.ray.OwnerFollowRayHook"
          (supplier #(cn.li.mcbase.entity.hook.ray.OwnerFollowRayHook.))
          "cn.li.mcbase.entity.hook.ray.NoopRayHook"
          (supplier #(cn.li.mcbase.entity.hook.ray.NoopRayHook.))}
    :marker {"cn.li.mcbase.entity.hook.marker.OwnerFollowMarkerHook"
             (supplier #(cn.li.mcbase.entity.hook.marker.OwnerFollowMarkerHook.))
             "cn.li.mcbase.entity.hook.marker.NoopMarkerHook"
             (supplier #(cn.li.mcbase.entity.hook.marker.NoopMarkerHook.))}}))

(defn register-all-hooks!
  []
  (install-base-hook-factories!)
  (hook-core/register-all-scripted-hooks!))
