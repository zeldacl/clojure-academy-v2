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
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.config :as cfg]
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
