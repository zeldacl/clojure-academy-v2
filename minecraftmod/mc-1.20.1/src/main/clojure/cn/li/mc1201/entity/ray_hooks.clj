(ns cn.li.mc1201.entity.ray-hooks
  "Shared registration for scripted ray hook strategies."
  (:require [cn.li.mcmod.entity.hook-catalog :as hook-catalog]
            [cn.li.mc1201.entity.hook-registry-core :as hook-core])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

(defonce ^:private ray-hooks-installed? (atom false))

(def ^:private impl-key->hook-class
  {:owner-follow "cn.li.mc1201.entity.hook.ray.OwnerFollowRayHook"})

(defn- collect-ray-hook-entries
  []
  (hook-core/collect-hook-entries
   {:entity-kind :scripted-ray
    :property-key :ray
    :label "scripted-ray"
    :resolve-hook-class (fn [{:keys [hook-props hook-id]}]
                          (let [impl-key (or (some-> (:hook-impl-key hook-props) hook-core/normalize-impl-key)
                                             (hook-catalog/ray-impl-key hook-id))]
                            {:hook-impl-key impl-key
                             :hook-class (or (some-> (:hook-class hook-props) str)
                                             (get impl-key->hook-class impl-key))}))}))

(defn- resolve-hook-conflicts
  [hook-entries]
  (hook-core/resolve-hook-conflicts "scripted-ray" hook-entries))

(defn register-all-ray-hooks!
  []
  (hook-core/register-hook-classes!
   {:installed?-atom ray-hooks-installed?
    :entries (-> (collect-ray-hook-entries)
                 (resolve-hook-conflicts))
    :register-fn ScriptedEntitySpecAccess/registerScriptedRayHookClass
    :success-label "Registered scripted ray hook"}))
