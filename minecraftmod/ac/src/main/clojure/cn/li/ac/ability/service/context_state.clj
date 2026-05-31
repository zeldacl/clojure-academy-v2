(ns cn.li.ac.ability.service.context-state
  "Server-side handlers for context key input lifecycle.

  This namespace executes skill runtime callbacks behind context messages:
  - MSG-SLOT-KEY-DOWN
  - MSG-SLOT-KEY-TICK
  - MSG-SLOT-KEY-UP
  - MSG-SLOT-KEY-ABORT

  It keeps input state transitions strict to avoid duplicated lifecycle calls."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.rules.cooldown-rules :as cd-rules]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(def INPUT-IDLE :idle)
(def INPUT-ACTIVE :active)
(def INPUT-RELEASED :released)
(def INPUT-ABORTED :aborted)

(def ^:private callback->action-key
  {:on-key-down  :down!
   :on-key-tick  :tick!
   :on-key-up    :up!
   :on-key-abort :abort!})

(def ^:private supported-patterns
  #{:instant :hold-charge-release :toggle :hold-channel :release-cast :charge-window :passive})

(defn- pattern-owned-spec?
  [spec]
  (and (keyword? (:pattern spec))
       (contains? supported-patterns (:pattern spec))))

(defn- resolved-session-id
  [owner]
  (or (:server-session-id owner)
      (:client-session-id owner)
      (let [session-id (:session-id owner)]
        (when (and (some? session-id) (not (vector? session-id)))
          session-id))
      (runtime-hooks/require-player-state-session-id "context-state")))

(defn- runtime-player-state
  ([player-id]
   (runtime-player-state nil player-id))
  ([owner player-id]
   (store/get-player-state* (resolved-session-id owner) player-id)))

(defn- event-payload
  ([ctx-map payload]
   (event-payload nil ctx-map payload))
  ([owner ctx-map payload]
   (let [player-id (:player-uuid ctx-map)
         skill-id (:skill-id ctx-map)
         exp (double (adata/get-skill-exp (get-in (runtime-player-state owner player-id)
                                                  [:ability-data])
                                          skill-id))]
     {:ctx-id (:id ctx-map)
      :server-id (:server-id ctx-map)
      :player-id player-id
      :player (:player payload)
      :skill-id skill-id
      :exp exp
      :payload payload})))

(defn- dispatch-skill-callback!
  ([ctx-map cb-key event-type payload]
   (dispatch-skill-callback! nil ctx-map cb-key event-type payload))
  ([owner ctx-map cb-key event-type payload]
   (when-let [spec (skill/get-skill (:skill-id ctx-map))]
     (let [evt* (event-payload owner ctx-map payload)
           action-key (get callback->action-key cb-key)
           callback-fn (get-in spec [:actions action-key])]
       (when (fn? callback-fn)
         (try
           (callback-fn evt*)
           (catch Exception e
             (log/warn "Skill callback failed" (:id spec) cb-key (ex-message e)))))))
   (evt/fire-ability-event!
    {:event/type event-type
     :event/side :server
     :ctx-id (:id ctx-map)
     :server-id (:server-id ctx-map)
     :player-id (:player-uuid ctx-map)
     :skill-id (:skill-id ctx-map)
     :payload payload})))

(defn- set-input-state!
  ([ctx-id state]
   (set-input-state! nil ctx-id state))
  ([owner ctx-id state]
   (let [ctx-map (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))
         player-id (:player-uuid ctx-map)
         session-id (resolved-session-id owner)
         result (when (and player-id session-id)
                  (command-rt/run-command-in-session!
                   session-id
                   player-id
                   {:command :context-set-input-state
                    :ctx-id (:id ctx-map)
                    :input-state state}))]
     (when (= :context-not-found (:rejected-reason result))
       (if owner
         (ctx/update-context-owned! owner ctx-id assoc :input-state state)
         (ctx/update-context! ctx-id assoc :input-state state)))
     (if owner
       (ctx/get-context owner ctx-id)
       (ctx/get-context ctx-id)))))

(defn- apply-main-cooldown!
  ([ctx-map]
   (apply-main-cooldown! nil ctx-map))
  ([owner ctx-map]
   (let [uuid (:player-uuid ctx-map)
         spec (skill/get-skill (:skill-id ctx-map))
         ctrl-id (or (:ctrl-id spec) (:skill-id ctx-map))
         cooldown-ticks (max 1 (int (or (:cooldown-ticks spec) 1)))
         session-id (resolved-session-id owner)]
     (command-rt/run-command-in-session! session-id
                                         uuid
                                         {:command :set-cooldown
                                          :ctrl-id ctrl-id
                                          :ticks cooldown-ticks
                                          :sub-id :main}))))

(defn- in-main-cooldown?
  ([ctx-map]
   (in-main-cooldown? nil ctx-map))
  ([owner ctx-map]
   (let [uuid (:player-uuid ctx-map)
         state (runtime-player-state owner uuid)
         spec (skill/get-skill (:skill-id ctx-map))
         ctrl-id (or (:ctrl-id spec) (:skill-id ctx-map))]
     (and state
          (cd-rules/in-cooldown? (:cooldown-data state) ctrl-id :main)))))

(defn should-apply-main-cooldown?
  "Pure policy helper: only skip cooldown when skill cooldown mode is explicit :manual."
  [skill-spec]
  (not= :manual (get-in skill-spec [:cooldown :mode])))

(defn should-terminate-context-on-key-up?
  "Pure policy helper: by default key-up terminates context.
  Skills can opt out via {:input-policy {:terminate-on-key-up? false}}."
  [skill-spec]
  (not (false? (get-in skill-spec [:input-policy :terminate-on-key-up?]))))

(defn should-keep-active-on-key-up?
  "Pure policy helper: when true, key-up keeps context in ACTIVE state
  after on-key-up callback settlement.

  This is primarily used by skills that have a post-release triggering phase
  driven by server tick.

  Note: this policy is only effective when context is not terminated on key-up."
  [skill-spec]
  (true? (get-in skill-spec [:input-policy :keep-active-on-key-up?])))

(defn handle-key-down!
  ([ctx-id payload]
   (handle-key-down! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (handle-key-down! nil ctx-id payload terminate-fn))
  ([owner ctx-id payload terminate-fn]
   (when-let [ctx-map (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (or (nil? (:input-state ctx-map))
                    (= (:input-state ctx-map) INPUT-IDLE)))
       (if (in-main-cooldown? owner ctx-map)
         (do
           (if owner
             (ctx/terminate-context! owner ctx-id terminate-fn)
             (ctx/terminate-context! ctx-id terminate-fn))
           false)
         (let [new-ctx (set-input-state! owner ctx-id INPUT-ACTIVE)]
           (dispatch-skill-callback! owner new-ctx :on-key-down evt/EVT-CONTEXT-KEY-DOWN payload)
           true))))))

(defn handle-key-tick!
  ([ctx-id payload]
   (handle-key-tick! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (handle-key-tick! nil ctx-id payload terminate-fn))
  ([owner ctx-id payload terminate-fn]
   (when-let [ctx-map (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (= (:input-state ctx-map) INPUT-ACTIVE))
       (dispatch-skill-callback! owner ctx-map :on-key-tick evt/EVT-CONTEXT-KEY-TICK payload)
       true))))

(defn handle-key-up!
  ([ctx-id payload]
   (handle-key-up! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (handle-key-up! nil ctx-id payload terminate-fn))
  ([owner ctx-id payload terminate-fn]
   (when-let [ctx-map (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))]
     (when (and (= (:status ctx-map) ctx/STATUS-ALIVE)
                (= (:input-state ctx-map) INPUT-ACTIVE))
       (let [released-ctx (set-input-state! owner ctx-id INPUT-RELEASED)]
         (dispatch-skill-callback! owner released-ctx :on-key-up evt/EVT-CONTEXT-KEY-UP payload)
         (let [latest-ctx (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))
               spec (skill/get-skill (:skill-id latest-ctx))
             pattern-handled? (boolean (and spec (pattern-owned-spec? spec)))]
           (when-not pattern-handled?
             (evt/fire-ability-event!
              (evt/make-skill-perform-event (:player-uuid released-ctx) (:skill-id released-ctx)))
             (when (should-apply-main-cooldown? spec)
               (apply-main-cooldown! owner released-ctx)))
           (if (should-terminate-context-on-key-up? spec)
             (if owner
               (ctx/terminate-context! owner ctx-id terminate-fn)
               (ctx/terminate-context! ctx-id terminate-fn))
             (if (should-keep-active-on-key-up? spec)
               (set-input-state! owner ctx-id INPUT-ACTIVE)
               (set-input-state! owner ctx-id INPUT-IDLE))))
         true)))))

(defn handle-key-abort!
  ([ctx-id payload]
   (handle-key-abort! ctx-id payload nil))
  ([ctx-id payload terminate-fn]
   (handle-key-abort! nil ctx-id payload terminate-fn))
  ([owner ctx-id payload terminate-fn]
   (when-let [ctx-map (if owner (ctx/get-context owner ctx-id) (ctx/get-context ctx-id))]
     (when (not= (:status ctx-map) ctx/STATUS-TERMINATED)
       (let [aborted-ctx (set-input-state! owner ctx-id INPUT-ABORTED)]
         (dispatch-skill-callback! owner aborted-ctx :on-key-abort evt/EVT-CONTEXT-KEY-ABORT payload)
         (if owner
           (ctx/terminate-context! owner ctx-id terminate-fn)
           (ctx/terminate-context! ctx-id terminate-fn))
         true)))))
