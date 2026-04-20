(ns cn.li.forge1201.entity.effect-hooks
  "Forge-side registration for scripted effect hook strategies."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.entity.dsl :as edsl])
  (:import [cn.li.forge1201.bridge ForgeRuntimeBridge]))

(defonce ^:private effect-hooks-installed? (atom false))

(def ^:private default-hook-classes
  {"intensify-arcs" "cn.li.forge1201.entity.effect.hooks.IntensifyArcsEffectHook"})

(defn- collect-effect-hook-entries
  []
  (->> (edsl/list-entities)
       (keep (fn [entity-id]
               (let [entity-spec  (edsl/get-entity entity-id)
                     effect-props (get-in entity-spec [:properties :effect])
                     hook-id      (some-> (:hook effect-props) name)
                     hook-class   (or (some-> (:hook-class effect-props) str)
                                      (get default-hook-classes hook-id))]
                 (when (= :scripted-effect (:entity-kind entity-spec))
                   (cond
                     (or (nil? effect-props) (empty? effect-props))
                     (log/warn "scripted-effect is missing :properties/:effect"
                               {:entity-id entity-id})

                     (or (nil? hook-id) (empty? hook-id))
                     (log/warn "scripted-effect is missing :effect/:hook"
                               {:entity-id entity-id})

                     (or (nil? hook-class) (empty? hook-class))
                     (log/warn "scripted-effect hook has no registered platform hook class"
                               {:entity-id entity-id})

                     :else
                     {:entity-id  entity-id
                      :hook-id    hook-id
                      :hook-class hook-class})))))
       distinct))

(defn- resolve-hook-conflicts
  [hook-entries]
  (->> hook-entries
       (group-by :hook-id)
       (keep (fn [[hook-id entries]]
               (let [classes (distinct (map :hook-class entries))]
                 (if (> (count classes) 1)
                   (do
                     (log/warn "scripted-effect hook-id has conflicting hook-class definitions; skipping registration"
                               {:hook-id hook-id
                                :definitions (mapv (fn [{:keys [entity-id hook-class]}]
                                                     {:entity-id entity-id
                                                      :hook-class hook-class})
                                                   entries)})
                     nil)
                   [hook-id (first classes)]))))))

(defn register-all-effect-hooks!
  []
  (when (compare-and-set! effect-hooks-installed? false true)
    (doseq [[hook-id class-name] (-> (collect-effect-hook-entries)
                                     (resolve-hook-conflicts))]
      (if (ForgeRuntimeBridge/registerScriptedEffectHookClass hook-id class-name)
        (log/info "Registered scripted effect hook" {:hook-id hook-id :class class-name})
        (log/error "Failed to register scripted effect hook" {:hook-id hook-id :class class-name}))))
  nil)
