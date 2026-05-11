(ns cn.li.mc1201.entity.marker-hooks
  "Shared registration for scripted marker hook strategies."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.entity.dsl :as edsl])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

(defonce ^:private marker-hooks-installed? (atom false))

(def ^:private default-hook-classes
  {"tp-marking" "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"
   "marker"     "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"})

(defn- collect-marker-hook-entries
  []
  (->> (edsl/list-entities)
       (keep (fn [entity-id]
               (let [entity-spec  (edsl/get-entity entity-id)
                     marker-props (get-in entity-spec [:properties :marker])
                     hook-id      (some-> (:hook marker-props) name)
                     hook-class   (or (some-> (:hook-class marker-props) str)
                                      (get default-hook-classes hook-id))]
                 (when (= :scripted-marker (:entity-kind entity-spec))
                   (cond
                     (or (nil? marker-props) (empty? marker-props))
                     (log/warn "scripted-marker is missing :properties/:marker" {:entity-id entity-id})

                     (or (nil? hook-id) (empty? hook-id))
                     (log/warn "scripted-marker is missing :marker/:hook" {:entity-id entity-id})

                     (or (nil? hook-class) (empty? hook-class))
                     (log/warn "scripted-marker hook has no registered platform hook class"
                               {:entity-id entity-id :hook-id hook-id})

                     :else
                     {:entity-id entity-id :hook-id hook-id :hook-class hook-class})))))
       distinct))

(defn register-all-marker-hooks!
  []
  (when (compare-and-set! marker-hooks-installed? false true)
    (doseq [{:keys [hook-id hook-class]} (collect-marker-hook-entries)]
      (if (ScriptedEntitySpecAccess/registerScriptedMarkerHookClass hook-id hook-class)
        (log/info "Registered scripted marker hook" {:hook-id hook-id :class hook-class})
        (log/error "Failed to register scripted marker hook" {:hook-id hook-id :class hook-class}))))
  nil)
