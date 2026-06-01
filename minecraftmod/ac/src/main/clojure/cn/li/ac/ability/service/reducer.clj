(ns cn.li.ac.ability.service.reducer
  "Pure command reducer for ability system.
  
  Takes a player-state map and a command map, returns:
    {:state   updated-player-state  (pure data)
     :events  [event-maps]          (to be fired by shell)
     :effects [effect-plans]}       (to be executed by shell)
  
  Sub-reducers are private and focused on a single concern.
  apply-command is the top-level dispatcher.
  
  No side effects, no atoms, no dynamic bindings, no requiring-resolve.
  All business logic delegates to the rules layer (rules/*.clj)."
  (:require [cn.li.ac.ability.rules.learning-rules :as learning]
            [cn.li.ac.ability.rules.resource-rules :as resource]
            [cn.li.ac.ability.rules.cooldown-rules :as cooldown]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.ac.ability.model.develop :as ddata]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.terminal.model :as terminal-model]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Outcome Builder Helpers
;; ============================================================================

(defn- ok
  "Build a successful reducer result."
  ([state]
   {:state state :events [] :effects []})
  ([state events]
   {:state state :events events :effects []})
  ([state events effects]
   {:state state :events events :effects effects}))

(defn- rejected
  "Build a no-op reducer result (command rejected)."
  ([state reason]
   {:state state :events [] :effects [] :rejected-reason reason}))

;; ============================================================================
;; Sub-Reducer: learn-skill
;; ============================================================================

(defn- cmd-learn-skill
  "Learn a skill for a player.
  
  Command fields:
    :skill-id       keyword
    :player-uuid    UUID-string
    :player-level   int
    :developer-type keyword :portable|:normal|:advanced
    :check-conditions? bool (default true)"
  [player-state {:keys [skill-id player-uuid player-level developer-type check-conditions?]
                 :or {check-conditions? true}}]
  (let [ability-data (:ability-data player-state)]
    
    ;; Already learned?
    (if (adata/is-learned? ability-data skill-id)
      (rejected player-state :already-learned)
      
      (let [skill-spec (skill-registry/get-skill skill-id)]
        
        ;; Skill exists?
        (if-not skill-spec
          (rejected player-state :unknown-skill)
          
          ;; Check conditions if requested
          (let [conditions-ok? (if check-conditions?
                                 (learning/can-learn? skill-spec ability-data
                                                      player-level developer-type)
                                 true)]
            (if-not conditions-ok?
              (rejected player-state :conditions-not-met)
              
              (let [new-ability-data (adata/learn-skill ability-data skill-id)
                  events [(evt/make-skill-learn-event player-uuid skill-id)]
                    effects [{:effect/type :network-send
                        :player-uuid player-uuid
                              :channel :ability/skill-learned
                              :payload {:player-uuid player-uuid :skill-id skill-id}}
                             {:effect/type :persist-state
                        :player-uuid player-uuid
                              :domain :ability-data}]]
                (ok (assoc player-state :ability-data new-ability-data)
                    events
                    effects)))))))))

;; ============================================================================
;; Sub-Reducer: level-up
;; ============================================================================

(defn- cmd-level-up
  "Attempt to level up based on current ability progress.
  
  Command fields:
    :player-uuid  UUID-string
    :force?       bool (default false) — skip threshold check"
  [player-state {:keys [player-uuid force?] :or {force? false}}]
  (let [ability-data (:ability-data player-state)
        cat-id (:category-id ability-data)
        current-level (:level ability-data)]
    
    (if (>= current-level (cfg/max-level))
      (rejected player-state :at-max-level)
      
      (let [skills (skill-query/get-controllable-skills-at-level cat-id current-level)
            cat-rate (category/get-prog-incr-rate cat-id)
            can-level? (or force?
                           (learning/can-level-up? ability-data skills cat-rate
                                                   (cfg/prog-incr-rate) (cfg/max-level)))]
        (if-not can-level?
          (rejected player-state :not-enough-progress)
          
          (let [{:keys [data old-level new-level]} (learning/perform-level-up ability-data)
                events [(evt/make-level-change-event player-uuid old-level new-level)]
                effects [{:effect/type :network-send
                          :channel :ability/level-up
                          :payload {:player-uuid player-uuid
                                    :old-level old-level
                                    :new-level new-level}}
                         {:effect/type :persist-state
                          :domain :ability-data}]]
            (ok (assoc player-state :ability-data data)
                events
                effects)))))))

;; ============================================================================
;; Sub-Reducer: add-skill-exp
;; ============================================================================

(defn- cmd-add-skill-exp
  "Add exp to a skill and accumulate level progress.
  
  Command fields:
    :player-uuid  UUID-string
    :skill-id     keyword
    :amount       float
    :source       keyword (optional, for audit events)"
  [player-state {:keys [player-uuid skill-id amount source]}]
  (let [ability-data (:ability-data player-state)
        skill-spec (skill-registry/get-skill skill-id)
        exp-incr-speed (or (:exp-incr-speed skill-spec) 1.0)
        global-rate (cfg/prog-incr-rate)
        
        {:keys [data exp-delta]} (learning/add-skill-exp
                                   ability-data skill-id amount
                                   exp-incr-speed global-rate)]
    
    (if (zero? exp-delta)
      (ok player-state)
      
      (let [events [{:event/type evt/EVT-SKILL-EXP-ADDED
                     :event/side :server
                     :player-uuid player-uuid
                     :skill-id skill-id
                     :amount exp-delta
                     :source source}
                    {:event/type evt/EVT-SKILL-EXP-CHANGED
                     :event/side :server
                     :player-uuid player-uuid
                     :skill-id skill-id
                     :new-exp (adata/get-skill-exp data skill-id)}]]
        (ok (assoc player-state :ability-data data) events [])))))

;; ============================================================================
;; Sub-Reducer: consume-resource
;; ============================================================================

(defn- cmd-consume-resource
  "Attempt to consume CP and/or overload.
  
  Command fields:
    :player-uuid  UUID-string
    :cp           float
    :overload     float
    :creative?    bool (default false)"
  [player-state {:keys [player-uuid cp overload creative?]
                 :or {cp 0.0 overload 0.0 creative? false}}]
  (let [ability-data (:ability-data player-state)
        resource-data (:resource-data player-state)
        level (:level ability-data 1)
        
        {:keys [data success? events-needed]}
        (resource/perform-resource resource-data
                                   (double overload)
                                   (double cp)
                                   creative?
                                   level
                                   (cfg/maxcp-incr-rate)
                                   (cfg/maxo-incr-rate))]
    
    (if-not success?
      {:state player-state :events [] :effects [] :success? false}
      
      (let [events (cond-> []
                     (some #{:overload-cap-hit} events-needed)
                     (conj {:event/type evt/EVT-OVERLOAD
                            :event/side :server
                            :player-uuid player-uuid}))]
        (assoc (ok (assoc player-state :resource-data data) events [])
               :success? true)))))

;; ============================================================================
;; Sub-Reducer: set-cooldown
;; ============================================================================

(defn- cmd-set-cooldown
  "Set a cooldown after a skill use.
  
  Command fields:
    :player-uuid  UUID-string
    :ctrl-id      keyword
    :ticks        int
    :sub-id       keyword (default :main)"
  [player-state {:keys [ctrl-id ticks sub-id] :or {sub-id :main}}]
  (let [cd-data (:cooldown-data player-state)
        {:keys [data]} (cooldown/set-cooldown cd-data ctrl-id ticks sub-id)]
    (ok (assoc player-state :cooldown-data data))))

;; ============================================================================
;; Sub-Reducer: set-activated
;; ============================================================================

(defn- cmd-set-activated
  "Toggle activation state.
  
  Command fields:
    :player-uuid  UUID-string
    :activated    bool"
  [player-state {:keys [player-uuid activated]}]
  (let [res-data (:resource-data player-state)
        {:keys [data events-needed]} (resource/set-activated res-data activated)
        events (mapv (fn [e]
                       (case e
                         :activate (evt/make-activate-event player-uuid)
                         :deactivate (evt/make-deactivate-event player-uuid)))
                     events-needed)]
    (ok (assoc player-state :resource-data data) events [])))

;; ============================================================================
;; Sub-Reducer: manage-preset
;; ============================================================================

(defn- cmd-set-preset-slot
  "Set a skill in a preset slot.
  
  Command fields:
    :preset-idx   int (0-3)
    :key-idx      int (0-3)
    :controllable [cat-id ctrl-id] or nil"
  [player-state {:keys [player-uuid preset-idx key-idx controllable]}]
  (let [
      preset-data (:preset-data player-state)
        new-preset-data (pdata/set-slot preset-data preset-idx key-idx controllable)]
    (ok (assoc player-state :preset-data new-preset-data)
      [(evt/make-preset-update-event player-uuid preset-idx key-idx controllable)]
        [{:effect/type :persist-state :domain :preset-data}])))

(defn- cmd-switch-preset
  "Switch to a different preset.
  
  Command fields:
    :player-uuid  UUID-string
    :preset-idx   int (0-3)"
  [player-state {:keys [player-uuid preset-idx]}]
    (let [preset-data (:preset-data player-state)
      old-preset (:active-preset preset-data 0)
        new-preset-data (pdata/set-active-preset preset-data preset-idx)
      events [(evt/make-preset-switch-event player-uuid old-preset preset-idx)]
        effects [{:effect/type :network-send
                  :channel :ability/preset-switched
                  :payload {:player-uuid player-uuid :preset-idx preset-idx}}]]
    (ok (assoc player-state :preset-data new-preset-data) events effects)))

;; ============================================================================
;; Sub-Reducer: server-tick
;; ============================================================================

(defn- cmd-server-tick
  "Advance one server tick for a player: recover resources, decrement cooldowns.
  
  Command fields:
    :cp-speed    float (optional override)
    :ol-speed    float (optional override)"
  [player-state {:keys [cp-speed ol-speed]}]
  (let [res-data (:resource-data player-state)
        cd-data (:cooldown-data player-state)
        eff-cp-speed (or cp-speed (cfg/cp-recover-speed))
        eff-ol-speed (or ol-speed (cfg/overload-recover-speed))
        
        res-result (resource/server-tick-recovery res-data eff-cp-speed eff-ol-speed)
        {:keys [data]} (cooldown/server-tick cd-data)]
    (ok (assoc player-state
               :resource-data (:data res-result)
               :cooldown-data data))))

;; ============================================================================
;; Sub-Reducer: context lifecycle
;; ============================================================================

(defn- cmd-register-context
  "Register a new context in the player's context-registry.
  
  Command fields:
    :ctx-id    keyword/uuid
    :skill-id  keyword
    :status    keyword (default :constructed)"
  [player-state {:keys [ctx-id skill-id status] :or {status :constructed}}]
    (let [ctx {:id ctx-id :skill-id skill-id :status status}
      new-state (assoc-in player-state [:context-registry ctx-id] ctx)
      uuid (:player-uuid player-state)]
    (ok new-state
      [(evt/make-context-registered-event uuid ctx-id skill-id status)]
      [])))

(defn- cmd-update-context-status
  "Update status of a context in the context-registry.
  
  Command fields:
    :ctx-id  keyword/uuid
    :status  keyword :constructed|:alive|:terminated
    :reason  keyword (termination reason)"
  [player-state {:keys [ctx-id status reason]}]
  (if-not (get-in player-state [:context-registry ctx-id])
    (rejected player-state :context-not-found)
    (let [old-status (get-in player-state [:context-registry ctx-id :status])
          new-state (update-in player-state [:context-registry ctx-id]
                               merge {:status status :terminated-reason reason})
          uuid (:player-uuid player-state)]
      (ok new-state
          [(evt/make-context-status-changed-event uuid ctx-id old-status status reason)]
          []))))

(defn- cmd-touch-context-keepalive
  "Update keepalive timestamp of a context in the context-registry.

  Command fields:
    :ctx-id      keyword/uuid
    :timestamp-ms long (optional, default now)"
  [player-state {:keys [ctx-id timestamp-ms]}]
  (if-not (get-in player-state [:context-registry ctx-id])
    (rejected player-state :context-not-found)
    (let [ts (long (or timestamp-ms (System/currentTimeMillis)))
          new-state (assoc-in player-state [:context-registry ctx-id :last-keepalive-ms] ts)]
      (ok new-state))))

(defn- cmd-purge-terminated-contexts
  "Remove :terminated contexts from the context-registry."
  [player-state _command]
  (let [registry (:context-registry player-state)
        live (into {}
                   (filter (fn [[_ ctx]]
                             (not= :terminated (:status ctx)))
                           registry))]
    (if (= (count registry) (count live))
      (ok (assoc player-state :context-registry live))
      (ok (assoc player-state :context-registry live)
          [(evt/make-context-purged-event (:player-uuid player-state)
                                          (- (count registry) (count live)))]
          []))))

;; ============================================================================
;; Sub-Reducer: resource recovery override
;; ============================================================================

(defn- cmd-restore-resource
  "Fully restore a player's resources (e.g. on death/respawn).
  
  No command fields required."
  [player-state _command]
  (let [res-data (:resource-data player-state)
        restored (rdata/recover-all res-data)]
    (ok (assoc player-state :resource-data restored))))

(defn- cmd-clear-all-cooldowns
  "Remove all cooldowns (e.g. on admin request)."
  [player-state _command]
  (ok (assoc player-state :cooldown-data (cdata/new-cooldown-data))))

;; ============================================================================
;; Sub-Reducer: admin/maintenance commands
;; ============================================================================

(defn- cmd-set-level
  "Set level directly (admin path) and fire level-change event when changed."
  [player-state {:keys [player-uuid level]}]
  (let [old-level (int (get-in player-state [:ability-data :level] 1))
        new-level (int level)]
    (if (= old-level new-level)
      (ok player-state)
      (ok (update player-state :ability-data adata/set-level new-level)
          [(evt/make-level-change-event player-uuid old-level new-level)]
          [{:effect/type :persist-state
            :player-uuid player-uuid
            :domain :ability-data}]))))

(defn- cmd-set-skill-exp
  "Set one skill exp directly (admin path)."
  [player-state {:keys [player-uuid skill-id amount]}]
  (let [new-state (update player-state :ability-data adata/set-skill-exp skill-id amount)]
    (ok new-state
        []
        [{:effect/type :persist-state
          :player-uuid player-uuid
          :domain :ability-data}])) )

(defn- cmd-unlearn-skill
  "Remove a skill from learned set and clear related preset slots."
  [player-state {:keys [player-uuid skill-id]}]
  (let [ability-before (:ability-data player-state)
        preset-before (:preset-data player-state)
        controllable (skill-query/controllable-key skill-id)
        new-ability (-> ability-before
                        (update :learned-skills disj skill-id)
                        (update :skill-exps dissoc skill-id))
        new-preset (if controllable
                     (reduce-kv (fn [data [preset-idx key-idx] slot]
                                  (if (= slot controllable)
                                    (pdata/set-slot data preset-idx key-idx nil)
                                    data))
                                preset-before
                                (:slots preset-before))
                     preset-before)
        changed? (not= [ability-before preset-before] [new-ability new-preset])]
    (if-not changed?
      (ok player-state)
      (ok (-> player-state
              (assoc :ability-data new-ability)
              (assoc :preset-data new-preset))
          []
          [{:effect/type :persist-state
            :player-uuid player-uuid
            :domain :ability-data}
           {:effect/type :persist-state
            :player-uuid player-uuid
            :domain :preset-data}]))))

(defn- cmd-recover-all
  "Fully recover CP/overload values."
  [player-state {:keys [player-uuid]}]
  (ok (update player-state :resource-data rdata/recover-all)
      []
      [{:effect/type :persist-state
        :player-uuid player-uuid
        :domain :resource-data}]))

(defn- cmd-reset-abilities
  "Reset ability/runtime-facing ability domains to defaults."
  [player-state {:keys [player-uuid]}]
  (let [old-category (get-in player-state [:ability-data :category-id])
        next-state (assoc player-state
                          :ability-data (adata/new-ability-data)
                          :resource-data (rdata/new-resource-data)
                          :cooldown-data (cdata/new-cooldown-data)
                          :preset-data (pdata/new-preset-data)
                          :develop-data (ddata/new-develop-data))
        events (if old-category
                 [(evt/make-category-change-event player-uuid old-category nil)]
                 [])]
    (ok next-state
        events
        [{:effect/type :persist-state
          :player-uuid player-uuid
          :domain :ability-data}
         {:effect/type :persist-state
          :player-uuid player-uuid
          :domain :resource-data}
         {:effect/type :persist-state
          :player-uuid player-uuid
          :domain :cooldown-data}
         {:effect/type :persist-state
          :player-uuid player-uuid
          :domain :preset-data}
         {:effect/type :persist-state
          :player-uuid player-uuid
          :domain :develop-data}])) )

(defn- cmd-change-category
  "Set category and clear preset slots.

  This command intentionally uses the default category transition behaviour.
  Special transitions that need custom level handling should remain in explicit
  application-level flows until they are modeled as first-class commands."
  [player-state {:keys [player-uuid new-category]}]
  (let [old-category (get-in player-state [:ability-data :category-id])]
    (if (= old-category new-category)
      (ok player-state)
      (ok (-> player-state
              (update :ability-data adata/set-category new-category)
              (update :preset-data pdata/clear-slots))
          [(evt/make-category-change-event player-uuid old-category new-category)]
          [{:effect/type :persist-state
            :player-uuid player-uuid
            :domain :ability-data}
           {:effect/type :persist-state
            :player-uuid player-uuid
            :domain :preset-data}]))))

(defn- cmd-change-category-with-level
  "Set category, force a target level, and clear preset slots.

  This models explicit category transforms previously handled by callback-style
  state mutation in item workflows."
  [player-state {:keys [player-uuid new-category new-level]}]
  (let [old-category (get-in player-state [:ability-data :category-id])
        target-level (int new-level)]
    (if (= old-category new-category)
      (ok player-state)
      (ok (-> player-state
              (update :ability-data
                      (fn [ability-data]
                        (-> ability-data
                            (adata/set-category new-category)
                            (adata/set-level target-level))))
              (update :preset-data pdata/clear-slots))
          [(evt/make-category-change-event player-uuid old-category new-category)]
          [{:effect/type :persist-state
            :player-uuid player-uuid
            :domain :ability-data}
           {:effect/type :persist-state
            :player-uuid player-uuid
            :domain :preset-data}]))))

  (defn- cmd-enforce-overload-floor
    "Ensure resource overload is not below floor.

    Command fields:
      :floor-value number"
    [player-state {:keys [floor-value]}]
    (let [floor (double (or floor-value 0.0))
          current (double (or (get-in player-state [:resource-data :cur-overload]) 0.0))]
      (if (< current floor)
        (ok (-> player-state
                (assoc-in [:resource-data :cur-overload] floor)
                (assoc-in [:resource-data :overload-fine] true)))
        (ok player-state))))

  (defn- cmd-sync-ability-data
    [player-state {:keys [ability-data]}]
    (if (some? ability-data)
      (ok (assoc player-state :ability-data ability-data))
      (rejected player-state :invalid-ability-data-sync)))

  (defn- cmd-sync-resource-data
    [player-state {:keys [resource-data]}]
    (if (some? resource-data)
      (ok (assoc player-state :resource-data resource-data))
      (rejected player-state :invalid-resource-data-sync)))

  (defn- cmd-sync-cooldown-data
    [player-state {:keys [cooldown-data]}]
    (if (some? cooldown-data)
      (ok (assoc player-state :cooldown-data cooldown-data))
      (rejected player-state :invalid-cooldown-data-sync)))

  (defn- cmd-sync-preset-data
    [player-state {:keys [preset-data]}]
    (if (some? preset-data)
      (ok (assoc player-state :preset-data preset-data))
      (rejected player-state :invalid-preset-data-sync)))

  (defn- cmd-sync-develop-data
    [player-state {:keys [develop-data]}]
    (if (some? develop-data)
      (ok (assoc player-state :develop-data develop-data))
      (rejected player-state :invalid-develop-data-sync)))

  (defn- cmd-sync-terminal-data
    [player-state {:keys [terminal-data]}]
    (if (some? terminal-data)
      (ok (assoc player-state terminal-model/state-key
                 (terminal-model/normalize-state terminal-data)))
      (rejected player-state :invalid-terminal-data-sync)))

  (defn- cmd-sync-context-registry
    [player-state {:keys [context-registry]}]
    (if (map? context-registry)
      (ok (assoc player-state :context-registry context-registry))
      (rejected player-state :invalid-context-registry-sync)))

  (defn- cmd-sync-runtime-data
    [player-state {:keys [runtime-data]}]
    (if (some? runtime-data)
      (ok (assoc player-state :runtime runtime-data))
      (rejected player-state :invalid-runtime-data-sync)))

  (defn- cmd-set-dirty-flag
    [player-state {:keys [dirty?] :or {dirty? false}}]
    (ok (assoc player-state :dirty? (boolean dirty?))))

  (defn- cmd-apply-server-tick-postprocess
    "Apply post-tick resolved domain snapshots produced by state-tick orchestration.

    This command is explicit and domain-scoped to server tick settlement,
    replacing ad-hoc use of generic set-* commands in tick pipelines."
    [player-state {:keys [ability-data resource-data cooldown-data develop-data]}]
    (ok (cond-> player-state
          (some? ability-data) (assoc :ability-data ability-data)
          (some? resource-data) (assoc :resource-data resource-data)
          (some? cooldown-data) (assoc :cooldown-data cooldown-data)
          (some? develop-data) (assoc :develop-data develop-data))))

  (defn- cmd-context-assoc-skill-state
    [player-state {:keys [ctx-id k v]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (let [key-path (if (vector? k) k [k])]
        (ok (assoc-in player-state (into [:context-registry ctx-id :skill-state] key-path) v)))))

  (defn- cmd-context-increment-skill-state
    [player-state {:keys [ctx-id k max]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
    (let [state-key (or k :charge-ticks)
      key-path (if (vector? state-key) state-key [state-key])
            max-v (long (or max Long/MAX_VALUE))
      current (long (or (get-in player-state (into [:context-registry ctx-id :skill-state] key-path)) 0))
            next-v (min max-v (inc current))]
      (ok (assoc-in player-state (into [:context-registry ctx-id :skill-state] key-path) next-v)))))

  (defn- cmd-context-set-toggle-state
    [player-state {:keys [ctx-id skill-id toggle-state]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (ok (assoc-in player-state [:context-registry ctx-id :skill-state :toggle skill-id]
                    toggle-state))))

  (defn- cmd-context-set-toggle-active
    [player-state {:keys [ctx-id skill-id active]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (ok (assoc-in player-state [:context-registry ctx-id :skill-state :toggle skill-id :active]
                    (boolean active)))))

  (defn- cmd-context-remove-toggle-state
    [player-state {:keys [ctx-id skill-id]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (ok (update-in player-state [:context-registry ctx-id :skill-state :toggle]
                     (fn [toggle-map]
                       (if (map? toggle-map)
                         (dissoc toggle-map skill-id)
                         toggle-map))))))

  (defn- cmd-context-clear-skill-state
    [player-state {:keys [ctx-id]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (ok (update-in player-state [:context-registry ctx-id] dissoc :skill-state))))

  (defn- cmd-context-set-input-state
    [player-state {:keys [ctx-id input-state]}]
    (if-not (get-in player-state [:context-registry ctx-id])
      (rejected player-state :context-not-found)
      (ok (assoc-in player-state [:context-registry ctx-id :input-state] input-state))))

  (defn- cmd-set-railgun-coin-judged-uuid
    [player-state {:keys [coin-uuid]}]
    (if (some? coin-uuid)
      (ok (assoc-in player-state [:runtime :railgun :coin-judged-uuid] coin-uuid))
      (rejected player-state :invalid-coin-uuid)))

  (defn- cmd-clear-railgun-coin-judged-uuid
    [player-state _]
    (let [railgun (get-in player-state [:runtime :railgun])]
      (if (map? railgun)
        (ok (assoc-in player-state [:runtime :railgun] (dissoc railgun :coin-judged-uuid)))
        (ok player-state))))

;; ============================================================================
;; Public Dispatcher
;; ============================================================================

(defmulti apply-command
  "Apply a command to player-state.
  
  Returns: {:state updated-state :events [event-maps] :effects [effect-plans]}
  
  Optional extra keys:
    :success?        – bool, present on resource-check commands
    :rejected-reason – keyword, present on rejected commands"
  (fn [_player-state command]
    (:command command)))

(defmethod apply-command :learn-skill     [ps cmd] (cmd-learn-skill ps cmd))
(defmethod apply-command :level-up        [ps cmd] (cmd-level-up ps cmd))
(defmethod apply-command :add-skill-exp   [ps cmd] (cmd-add-skill-exp ps cmd))
(defmethod apply-command :consume-resource [ps cmd] (cmd-consume-resource ps cmd))
(defmethod apply-command :set-cooldown    [ps cmd] (cmd-set-cooldown ps cmd))
(defmethod apply-command :set-activated   [ps cmd] (cmd-set-activated ps cmd))
(defmethod apply-command :set-preset-slot [ps cmd] (cmd-set-preset-slot ps cmd))
(defmethod apply-command :switch-preset   [ps cmd] (cmd-switch-preset ps cmd))
(defmethod apply-command :server-tick     [ps cmd] (cmd-server-tick ps cmd))
(defmethod apply-command :register-context [ps cmd] (cmd-register-context ps cmd))
(defmethod apply-command :update-context-status [ps cmd] (cmd-update-context-status ps cmd))
(defmethod apply-command :touch-context-keepalive [ps cmd] (cmd-touch-context-keepalive ps cmd))
(defmethod apply-command :purge-terminated-contexts [ps cmd] (cmd-purge-terminated-contexts ps cmd))
(defmethod apply-command :restore-resource [ps cmd] (cmd-restore-resource ps cmd))
(defmethod apply-command :clear-all-cooldowns [ps cmd] (cmd-clear-all-cooldowns ps cmd))
(defmethod apply-command :set-level [ps cmd] (cmd-set-level ps cmd))
(defmethod apply-command :set-skill-exp [ps cmd] (cmd-set-skill-exp ps cmd))
(defmethod apply-command :unlearn-skill [ps cmd] (cmd-unlearn-skill ps cmd))
(defmethod apply-command :recover-all [ps cmd] (cmd-recover-all ps cmd))
(defmethod apply-command :reset-abilities [ps cmd] (cmd-reset-abilities ps cmd))
(defmethod apply-command :change-category [ps cmd] (cmd-change-category ps cmd))
(defmethod apply-command :change-category-with-level [ps cmd] (cmd-change-category-with-level ps cmd))
(defmethod apply-command :enforce-overload-floor [ps cmd] (cmd-enforce-overload-floor ps cmd))
(defmethod apply-command :sync-ability-data [ps cmd] (cmd-sync-ability-data ps cmd))
(defmethod apply-command :sync-resource-data [ps cmd] (cmd-sync-resource-data ps cmd))
(defmethod apply-command :sync-cooldown-data [ps cmd] (cmd-sync-cooldown-data ps cmd))
(defmethod apply-command :sync-preset-data [ps cmd] (cmd-sync-preset-data ps cmd))
(defmethod apply-command :sync-develop-data [ps cmd] (cmd-sync-develop-data ps cmd))
(defmethod apply-command :sync-terminal-data [ps cmd] (cmd-sync-terminal-data ps cmd))
(defmethod apply-command :sync-context-registry [ps cmd] (cmd-sync-context-registry ps cmd))
(defmethod apply-command :sync-runtime-data [ps cmd] (cmd-sync-runtime-data ps cmd))
(defmethod apply-command :set-dirty-flag [ps cmd] (cmd-set-dirty-flag ps cmd))
(defmethod apply-command :apply-server-tick-postprocess [ps cmd] (cmd-apply-server-tick-postprocess ps cmd))
(defmethod apply-command :context-assoc-skill-state [ps cmd] (cmd-context-assoc-skill-state ps cmd))
(defmethod apply-command :context-increment-skill-state [ps cmd] (cmd-context-increment-skill-state ps cmd))
(defmethod apply-command :context-set-toggle-state [ps cmd] (cmd-context-set-toggle-state ps cmd))
(defmethod apply-command :context-set-toggle-active [ps cmd] (cmd-context-set-toggle-active ps cmd))
(defmethod apply-command :context-remove-toggle-state [ps cmd] (cmd-context-remove-toggle-state ps cmd))
(defmethod apply-command :context-clear-skill-state [ps cmd] (cmd-context-clear-skill-state ps cmd))
(defmethod apply-command :context-set-input-state [ps cmd] (cmd-context-set-input-state ps cmd))
(defmethod apply-command :set-railgun-coin-judged-uuid [ps cmd] (cmd-set-railgun-coin-judged-uuid ps cmd))
(defmethod apply-command :clear-railgun-coin-judged-uuid [ps cmd] (cmd-clear-railgun-coin-judged-uuid ps cmd))

(defmethod apply-command :default
  [player-state command]
  (log/warn "Unknown ability command" (:command command))
  (ok player-state))

;; ============================================================================
;; Batch Dispatcher
;; ============================================================================

(defn apply-commands
  "Apply a sequence of commands to player-state, accumulating events and effects.
  
  Returns: {:state final-state :events [all-events] :effects [all-effects]}"
  [player-state commands]
  (reduce (fn [{:keys [state events effects]} command]
            (let [result (apply-command state command)]
              {:state   (:state result)
               :events  (into events (:events result))
               :effects (into effects (:effects result))}))
          {:state player-state :events [] :effects []}
          commands))
