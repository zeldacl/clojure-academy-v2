(ns cn.li.mc1201.entity.marker-hooks
  "Shared registration for scripted marker hook strategies."
  (:require [cn.li.mc1201.entity.hook-registry-core :as hook-core])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

(defonce ^:private marker-hooks-installed? (atom false))

(def ^:private default-hook-classes
  {"tp-marking" "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"
   "marker"     "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"})

(defn- collect-marker-hook-entries
  []
  (hook-core/collect-hook-entries
   {:entity-kind :scripted-marker
    :property-key :marker
    :label "scripted-marker"
    :resolve-hook-class (fn [{:keys [hook-props hook-id]}]
                          {:hook-class (or (some-> (:hook-class hook-props) str)
                                           (get default-hook-classes hook-id))})}))

(defn register-all-marker-hooks!
  []
  (hook-core/register-hook-classes!
   {:installed?-atom marker-hooks-installed?
    :entries (->> (collect-marker-hook-entries)
                  (map (juxt :hook-id :hook-class)))
    :register-fn ScriptedEntitySpecAccess/registerScriptedMarkerHookClass
    :success-label "Registered scripted marker hook"}))
