(ns cn.li.mc1201.entity.hooks
  "Versioned entry: install hook class package prefix then register shared hooks."
  (:require [cn.li.mcbase.entity.hook-registry-core :as hook-core]
            [cn.li.mcbase.entity.hooks :as shared-hooks]))

(defn- install-version-hook-factories!
  []
  (hook-core/install-hook-factories!
   {:effect {"cn.li.mc1201.entity.hook.effect.TieredArcsEffectHook"
             (reify java.util.function.Supplier (get [_] (cn.li.mc1201.entity.hook.effect.TieredArcsEffectHook.)))
             "cn.li.mc1201.entity.hook.effect.OwnerOrbitEffectHook"
             (reify java.util.function.Supplier (get [_] (cn.li.mc1201.entity.hook.effect.OwnerOrbitEffectHook.)))}}))

(defn register-all-hooks!
  []
  (hook-core/install-hook-class-prefix! "cn.li.mc1201")
  (install-version-hook-factories!)
  (shared-hooks/register-all-hooks!))
