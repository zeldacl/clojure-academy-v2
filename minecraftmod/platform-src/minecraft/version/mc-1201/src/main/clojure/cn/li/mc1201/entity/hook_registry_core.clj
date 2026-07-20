(ns cn.li.mc1201.entity.hook-registry-core
  "Client scripted entity hook class registration (effect / ray / marker)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.entity.hook-resolver :as hook-resolver]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

(defn normalize-impl-key
  [impl-key]
  (cond
    (keyword? impl-key) impl-key
    (string? impl-key) (keyword impl-key)
    :else nil))

(defn collect-hook-entries
  [{:keys [entity-kind property-key label resolve-hook-class]}]
  (->> (edsl/list-entities)
       (keep (fn [entity-id]
               (let [entity-spec (edsl/get-entity entity-id)
                     hook-props (get-in entity-spec [:properties property-key])
                     hook-id (some-> (:hook hook-props) name)
                     resolved (when hook-props
                                (resolve-hook-class {:entity-id entity-id
                                                     :entity-spec entity-spec
                                                     :hook-props hook-props
                                                     :hook-id hook-id}))]
                 (when (= entity-kind (:entity-kind entity-spec))
                   (cond
                     (or (nil? hook-props) (empty? hook-props))
                     (log/warn (str label " is missing :properties/" (name property-key))
                               {:entity-id entity-id})

                     (or (nil? hook-id) (empty? hook-id))
                     (log/warn (str label " is missing :" (name property-key) "/:hook")
                               {:entity-id entity-id})

                     (or (nil? (:hook-class resolved)) (empty? (:hook-class resolved)))
                     (log/warn (str label " hook has no registered platform hook class")
                               (merge {:entity-id entity-id :hook-id hook-id}
                                      (select-keys resolved [:hook-impl-key])))

                     :else
                     (merge {:entity-id entity-id
                             :hook-id hook-id}
                            resolved))))))
       distinct))

(defn resolve-hook-conflicts
  [label hook-entries]
  (->> hook-entries
       (group-by #(get % :hook-id))
       (keep (fn [[hook-id entries]]
               (let [classes (distinct (map #(get % :hook-class) entries))]
                 (if (> (count classes) 1)
                   (do
                     (log/warn (str label " hook-id has conflicting hook-class definitions; skipping registration")
                               {:hook-id hook-id
                                :definitions (mapv (fn [{:keys [entity-id hook-class]}]
                                                     {:entity-id entity-id
                                                      :hook-class hook-class})
                                                   entries)})
                     nil)
                   [hook-id (first classes)]))))))

(defn register-hook-classes!
  "Register one scripted-hook kind's Java hook classes exactly once per
   process (registerScripted*HookClass is a Java-side static registry, not
   Framework-scoped — must not redo on Framework reinjection)."
  [{:keys [install-key entries register-fn success-label]}]
  (install/process-once! [::scripted-hook install-key]
    #(doseq [[hook-id class-name] entries]
       (if (register-fn hook-id class-name)
         (log/info success-label {:hook-id hook-id :class class-name})
         (log/error (str "Failed to register " (str/lower-case success-label))
                    {:hook-id hook-id :class class-name}))))
  nil)

(def scripted-hook-specs
  "Data-driven specs for scripted entity hook class registration."
  {:effect {:entity-kind :scripted-effect
            :property-key :effect
            :label "scripted-effect"
            :catalog-impl-key-fn #(hook-resolver/resolve-impl-key :effect %)
            :impl-key->hook-class {:tiered-arcs "cn.li.mc1201.entity.hook.effect.TieredArcsEffectHook"
                                   :owner-offset "cn.li.mc1201.entity.hook.effect.OwnerOffsetEffectHook"
                                   :generic-arc "cn.li.mc1201.entity.hook.effect.GenericArcEffectHook"
                                   :owner-orbit "cn.li.mc1201.entity.hook.effect.OwnerOrbitEffectHook"
                                   :noop "cn.li.mc1201.entity.hook.effect.NoopEffectHook"
                                   :vertical-ballistic "cn.li.mc1201.entity.hook.effect.NoopEffectHook"}
            :conflict-mode :by-hook-id
            :install-key :effect
            :register-fn ScriptedEntitySpecAccess/registerScriptedEffectHookClass
            :success-label "Registered scripted effect hook"}
   :ray {:entity-kind :scripted-ray
         :property-key :ray
         :label "scripted-ray"
         :catalog-impl-key-fn #(hook-resolver/resolve-impl-key :ray %)
         :impl-key->hook-class {:owner-follow "cn.li.mc1201.entity.hook.ray.OwnerFollowRayHook"}
         :conflict-mode :by-hook-id
         :install-key :ray
         :register-fn ScriptedEntitySpecAccess/registerScriptedRayHookClass
         :success-label "Registered scripted ray hook"}
   :marker {:entity-kind :scripted-marker
            :property-key :marker
            :label "scripted-marker"
            :catalog-impl-key-fn #(hook-resolver/resolve-impl-key :marker %)
            :impl-key->hook-class {:owner-follow-marker "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"}
            :conflict-mode :allow-duplicates
            :install-key :marker
            :register-fn ScriptedEntitySpecAccess/registerScriptedMarkerHookClass
            :success-label "Registered scripted marker hook"}})

(defn- resolve-scripted-hook-class
  [{:keys [catalog-impl-key-fn impl-key->hook-class]}
   {:keys [hook-props hook-id]}]
  (let [impl-key (or (some-> (:hook-impl-key hook-props) normalize-impl-key)
                     (when catalog-impl-key-fn
                       (catalog-impl-key-fn hook-id)))
        hook-class (or (some-> (:hook-class hook-props) str)
                       (when impl-key
                         (get impl-key->hook-class impl-key)))]
    (cond-> {:hook-class hook-class}
      impl-key (assoc :hook-impl-key impl-key))))

(defn collect-scripted-hook-entries
  "Collect platform hook-class registration entries for one scripted hook kind."
  [hook-kind]
  (let [spec (or (get scripted-hook-specs hook-kind)
                 (throw (ex-info "Unknown scripted hook kind" {:hook-kind hook-kind})))]
    (collect-hook-entries
     (assoc spec :resolve-hook-class #(resolve-scripted-hook-class spec %)))))

(defn- registration-entries
  [{:keys [label conflict-mode]} hook-entries]
  (case conflict-mode
    :by-hook-id (resolve-hook-conflicts label hook-entries)
    :allow-duplicates (->> hook-entries
                           (map #(vector (get % :hook-id) (get % :hook-class))))
    (throw (ex-info "Unknown scripted hook conflict mode"
                    {:label label :conflict-mode conflict-mode}))))

(defn register-scripted-hook-kind!
  "Register one scripted entity hook kind by spec key (:effect, :ray, :marker)."
  [hook-kind]
  (let [{:keys [install-key register-fn success-label] :as spec}
        (or (get scripted-hook-specs hook-kind)
            (throw (ex-info "Unknown scripted hook kind" {:hook-kind hook-kind})))]
    (register-hook-classes!
     {:install-key install-key
      :entries (registration-entries spec (collect-scripted-hook-entries hook-kind))
      :register-fn register-fn
      :success-label success-label})))

(defn register-all-scripted-hooks!
  "Register all scripted entity hook kinds declared in `scripted-hook-specs`."
  []
  (doseq [hook-kind [:effect :ray :marker]]
    (register-scripted-hook-kind! hook-kind))
  nil)
