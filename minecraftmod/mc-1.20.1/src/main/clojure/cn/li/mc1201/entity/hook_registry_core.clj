(ns cn.li.mc1201.entity.hook-registry-core
  "Entity hook registration and lifecycle management.
   
   Hooks are functions that react to entity-related events and can be applied
   to abstract IEntity instances. This registry provides:
   - Hook registration for different event types
   - Hook execution with proper error handling
   - Hook metadata and documentation
   - Lifecycle management (enable/disable hooks)"
  (:require [clojure.string :as str]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.entity.hook-resolver :as hook-resolver]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.entity ScriptedEntitySpecAccess]))

;; ============================================================================
;; Hook Event Types
;; ============================================================================

(def hook-event-types
  "Supported entity hook event types"
  {:on-entity-tick "Called each tick for entity"
   :on-entity-damage "Called when entity takes damage"
   :on-entity-heal "Called when entity is healed"
   :on-entity-death "Called when entity dies"
   :on-potion-effect-added "Called when potion effect added"
   :on-potion-effect-removed "Called when potion effect removed"
   :on-player-login "Called when player logs in"
   :on-player-logout "Called when player logs out"
   :on-player-gamemode-changed "Called when player gamemode changes"
   :on-player-dimension-change "Called when player changes dimension"
   :on-living-entity-spawn "Called when living entity spawns"
   :on-living-entity-despawn "Called when living entity despawns"})

;; ============================================================================
;; Hook Registry
;; ============================================================================

(defonce ^:private hook-registry
  (atom {}))

(defonce ^:private hook-metadata
  (atom {}))

;; ============================================================================
;; Hook Registration
;; ============================================================================

(defn register-hook!
  "Register an entity hook for an event type.
   
   Parameters:
   - event-type: keyword from hook-event-types (:on-entity-damage, etc.)
   - hook-id: unique keyword identifier for this hook
   - hook-fn: function to call when event occurs
   - options: map with optional keys:
       :priority - hook priority (default :normal)
       :doc - documentation string
       :enabled? - whether hook starts enabled (default true)
   
   Hook function signature depends on event-type:
   - :on-entity-tick (entity tick-count)
   - :on-entity-damage (entity damage source-keyword)
   - :on-entity-death (entity damage-source-keyword)
   - :on-player-login (player)
   - :on-player-logout (player)
   - etc."
  [event-type hook-id hook-fn & [options]]
  (when-not (contains? hook-event-types event-type)
    (throw (ex-info "Unknown hook event type" {:type event-type})))
  
  (when-not (keyword? hook-id)
    (throw (ex-info "Hook ID must be a keyword" {:id hook-id})))
  
  (when-not (fn? hook-fn)
    (throw (ex-info "Hook must be a function" {:id hook-id})))
  
  (let [opts (or options {})
        priority (get opts :priority :normal)
        doc (get opts :doc "")
        enabled? (get opts :enabled? true)]
    
    (log/info "Registering entity hook"
              {:type event-type :id hook-id :priority priority})
    
    ;; Register hook in registry
    (swap! hook-registry
           (fn [registry]
             (update registry event-type
                     (fn [hooks]
                       (conj (or hooks [])
                             {:fn hook-fn
                              :id hook-id
                              :priority priority
                              :enabled? enabled?})))))
    
    ;; Store metadata
    (swap! hook-metadata
           assoc hook-id
           {:event-type event-type
            :priority priority
            :doc doc
            :enabled? enabled?})))

(defn register-hook-group!
  "Register multiple hooks at once.
   
   hooks-list: sequence of [event-type hook-id hook-fn options] tuples
   
   Example:
   (register-hook-group!
     [[:on-entity-damage :my-damage-handler my-fn {:priority :high}]
      [:on-entity-death :my-death-handler death-fn {:priority :normal}]])"
  [hooks-list]
  (doseq [[event-type hook-id hook-fn opts] hooks-list]
    (register-hook! event-type hook-id hook-fn opts)))

;; ============================================================================
;; Hook Execution
;; ============================================================================

(defn call-hooks
  "Execute all hooks for an event type.
   
   Parameters:
   - event-type: keyword identifying event (:on-entity-damage, etc.)
   - args: arguments to pass to each hook function
   
   Returns sequence of hook results (nil if hook disabled or error).
   Logs errors but doesn't throw - ensures other hooks still execute."
  [event-type & args]
  (let [hooks (get (deref hook-registry) event-type [])
        sorted-hooks (sort-by :priority hooks)]
    (for [hook sorted-hooks]
      (try
        (if (:enabled? hook)
          (apply (:fn hook) args)
          nil)
        (catch Exception e
          (log/error "Hook execution error"
                     {:event-type event-type
                      :hook-id (:id hook)
                      :error (str e)})
          nil)))))

(defn call-hooks-if-enabled
  "Execute hooks only if all are enabled (useful for critical paths).
   Throws if any hook is disabled."
  [event-type & args]
  (let [hooks (get (deref hook-registry) event-type [])]
    (when-let [disabled-hooks (seq (filter #(not (:enabled? %)) hooks))]
      (throw (ex-info "Cannot execute hooks - some are disabled"
                      {:event-type event-type
                       :disabled-count (count disabled-hooks)})))
    (apply call-hooks event-type args)))

;; ============================================================================
;; Hook Management
;; ============================================================================

(defn enable-hook!
  "Enable a specific hook"
  [hook-id]
  (swap! hook-metadata
         (fn [meta]
           (update meta hook-id assoc :enabled? true))))

(defn disable-hook!
  "Disable a specific hook"
  [hook-id]
  (swap! hook-metadata
         (fn [meta]
           (update meta hook-id assoc :enabled? false))))

(defn is-hook-enabled?
  "Check if a hook is enabled"
  [hook-id]
  (get-in (deref hook-metadata) [hook-id :enabled?] true))

(defn get-hooks-for-event
  "Get all hooks registered for an event type"
  [event-type]
  (get (deref hook-registry) event-type []))

(defn list-all-hooks
  "List all registered hooks with metadata"
  []
  (deref hook-metadata))

(defn unregister-hook!
  "Unregister a hook by ID"
  [hook-id]
  (let [event-type (get-in (deref hook-metadata) [hook-id :event-type])]
    (when event-type
      (swap! hook-registry
             (fn [registry]
               (update registry event-type
                       (fn [hooks]
                         (filterv #(not= (:id %) hook-id) hooks)))))
      
      (swap! hook-metadata dissoc hook-id))))

(defn clear-hooks!
  "Clear all hooks (for testing)"
  []
  (reset! hook-registry {})
  (reset! hook-metadata {}))

(defn clear-event-hooks!
  "Clear all hooks for a specific event type"
  [event-type]
  (let [hook-ids (map :id (get @hook-registry event-type []))]
    (doseq [id hook-ids]
      (unregister-hook! id))))

;; ============================================================================
;; Hook Utilities
;; ============================================================================

(defn hook-statistics
  "Get statistics about registered hooks"
  []
  (let [registry (deref hook-registry)
        metadata (deref hook-metadata)]
    {:total-hooks (count metadata)
     :total-event-types (count registry)
     :events-breakdown (into {}
                             (for [[event-type hooks] registry]
                               [event-type {:count (count hooks)
                                            :enabled (count (filter :enabled? hooks))}]))
     :hook-count-by-priority (frequencies (map :priority (vals metadata)))}))
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
       (group-by :hook-id)
       (keep (fn [[hook-id entries]]
               (let [classes (distinct (map :hook-class entries))]
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
  [{:keys [installed?-atom entries register-fn success-label]}]
  (when (compare-and-set! installed?-atom false true)
    (doseq [[hook-id class-name] entries]
      (if (register-fn hook-id class-name)
        (log/info success-label {:hook-id hook-id :class class-name})
        (log/error (str "Failed to register " (str/lower-case success-label))
                   {:hook-id hook-id :class class-name}))))
  nil)

(defonce ^:private scripted-hook-install-state
  {:effect (atom false)
   :ray (atom false)
   :marker (atom false)})

(def scripted-hook-specs
  "Data-driven specs for scripted entity hook class registration.

  Adding a new hook kind should be a spec addition here, not a new wrapper
  namespace with duplicated collect/register plumbing."
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
            :installed?-atom (:effect scripted-hook-install-state)
            :register-fn ScriptedEntitySpecAccess/registerScriptedEffectHookClass
            :success-label "Registered scripted effect hook"}
   :ray {:entity-kind :scripted-ray
         :property-key :ray
         :label "scripted-ray"
         :catalog-impl-key-fn #(hook-resolver/resolve-impl-key :ray %)
         :impl-key->hook-class {:owner-follow "cn.li.mc1201.entity.hook.ray.OwnerFollowRayHook"}
         :conflict-mode :by-hook-id
         :installed?-atom (:ray scripted-hook-install-state)
         :register-fn ScriptedEntitySpecAccess/registerScriptedRayHookClass
         :success-label "Registered scripted ray hook"}
   :marker {:entity-kind :scripted-marker
            :property-key :marker
            :label "scripted-marker"
            :catalog-impl-key-fn #(hook-resolver/resolve-impl-key :marker %)
            :impl-key->hook-class {:owner-follow-marker "cn.li.mc1201.entity.hook.marker.OwnerFollowMarkerHook"}
            :conflict-mode :allow-duplicates
            :installed?-atom (:marker scripted-hook-install-state)
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
                           (map (juxt :hook-id :hook-class)))
    (throw (ex-info "Unknown scripted hook conflict mode"
                    {:label label :conflict-mode conflict-mode}))))

(defn register-scripted-hook-kind!
  "Register one scripted entity hook kind by spec key (:effect, :ray, :marker)."
  [hook-kind]
  (let [{:keys [installed?-atom register-fn success-label] :as spec}
        (or (get scripted-hook-specs hook-kind)
            (throw (ex-info "Unknown scripted hook kind" {:hook-kind hook-kind})))]
    (register-hook-classes!
     {:installed?-atom installed?-atom
      :entries (registration-entries spec (collect-scripted-hook-entries hook-kind))
      :register-fn register-fn
      :success-label success-label})))

(defn register-all-scripted-hooks!
  "Register all scripted entity hook kinds declared in `scripted-hook-specs`."
  []
  (doseq [hook-kind [:effect :ray :marker]]
    (register-scripted-hook-kind! hook-kind))
  nil)
