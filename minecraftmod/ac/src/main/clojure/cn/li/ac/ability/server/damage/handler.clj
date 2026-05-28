(ns cn.li.ac.ability.server.damage.handler
  "Damage handler registration and management for skills.

  Skills can register damage handlers to intercept and modify incoming damage.
  This module manages the registration and provides utilities for common patterns.

  No Minecraft imports."
  (:require [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.mcmod.platform.damage-interception :as damage-interception]
            [cn.li.mcmod.util.log :as log]))

(defn register-toggle-damage-handler!
  "Register a damage handler for a toggle skill.
  The handler will only be called if the toggle skill is active.

  Parameters:
  - handler-id: keyword identifier (e.g. :vec-deviation-damage)
  - skill-id: keyword skill identifier (e.g. :vec-deviation)
  - handler-fn: (fn [player-id attacker-id damage damage-source] -> [modified-damage metadata])
  - priority: int (lower = earlier, default 100)

  Returns: true if registered successfully"
  ([handler-id skill-id handler-fn]
   (register-toggle-damage-handler! handler-id skill-id handler-fn 100))
  ([handler-id skill-id handler-fn priority]
   (when damage-interception/*damage-interception*
     (let [wrapped-handler (fn [player-id attacker-id damage damage-source]
                            ;; Check if toggle skill is active by looking for active contexts
                            (if (ps/get-player-state player-id)
                              ;; Try to find an active context with this toggle skill
                              (let [active-contexts (ctx/get-all-contexts)
                                    player-contexts (filter (fn [[_ctx-id ctx-data]]
                                                             (= (:player-uuid ctx-data) player-id))
                                                           active-contexts)
                                    has-active-toggle? (some (fn [[_ctx-id ctx-data]]
                                                              (toggle/is-toggle-active? ctx-data skill-id))
                                                            player-contexts)]
                                (if has-active-toggle?
                                  ;; Toggle is active - call handler
                                  (try
                                    (handler-fn player-id attacker-id damage damage-source)
                                    (catch Exception e
                                      (log/warn "Toggle damage handler" handler-id "failed:" (ex-message e))
                                      [damage nil]))
                                  ;; Toggle not active - pass through
                                  [damage nil]))
                              ;; No player state - pass through
                              [damage nil]))]
       (damage-interception/register-damage-handler! damage-interception/*damage-interception*
                                                     handler-id
                                                     wrapped-handler
                                                     priority)))))

(defn unregister-damage-handler!
  "Unregister a damage handler.

  Parameters:
  - handler-id: keyword identifier

  Returns: true if unregistered successfully"
  [handler-id]
  (when damage-interception/*damage-interception*
    (damage-interception/unregister-damage-handler! damage-interception/*damage-interception*
                                                    handler-id)))

;; ============================================================================
;; Attack-cancel-check registry
;; ============================================================================

;; ============================================================================
;; Attack-cancel-check registry
;; ============================================================================

(defn default-attack-check-registries-runtime-state
  []
  {:cancel-checks {}
   :precheck-side-effects {}
   :frozen? false})

(defn create-attack-check-registries-runtime
  ([] (create-attack-check-registries-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-attack-check-registries-runtime-state))}}]
   {::runtime ::attack-check-registries-runtime
    :state* state*}))

(def ^:dynamic *attack-check-registries-runtime* nil)

(defonce ^:private installed-attack-check-registries-runtime
  (create-attack-check-registries-runtime))

(defn call-with-attack-check-registries-runtime
  [runtime f]
  (when-not (and (map? runtime)
                 (= ::attack-check-registries-runtime (::runtime runtime))
                 (some? (:state* runtime)))
    (throw (ex-info "Expected attack check registries runtime" {:runtime runtime})))
  (binding [*attack-check-registries-runtime* runtime]
    (f)))

(defn- current-attack-check-registries-runtime
  []
  (or *attack-check-registries-runtime*
      installed-attack-check-registries-runtime))

(defn- attack-check-registries-state-atom
  []
  (:state* (current-attack-check-registries-runtime)))

(defn- attack-check-registries-state-snapshot
  []
  @(attack-check-registries-state-atom))

(defn- update-attack-check-registries-state!
  [f & args]
  (apply swap! (attack-check-registries-state-atom) f args))

(defn- assert-registries-open!
  []
  (when (:frozen? (attack-check-registries-state-snapshot))
    (throw (ex-info "Attack check registries are frozen" {}))))

(defn attack-check-registries-snapshot
  []
  (attack-check-registries-state-snapshot))

(defn reset-attack-check-registries-for-test!
  ([] (reset-attack-check-registries-for-test! {}))
  ([{:keys [cancel-checks precheck-side-effects frozen?]
     :or {cancel-checks {} precheck-side-effects {} frozen? false}}]
   (reset! (attack-check-registries-state-atom)
           {:cancel-checks cancel-checks
            :precheck-side-effects precheck-side-effects
            :frozen? frozen?})
   nil))

(defn freeze-attack-check-registries!
  []
  (update-attack-check-registries-state! assoc :frozen? true)
  nil)

(defn register-attack-cancel-check!
  "Register a predicate that decides whether an attack should be cancelled.
  Content skills call this at load time."
  [check-id check-fn]
  (when-not (contains? (:cancel-checks (attack-check-registries-state-snapshot)) check-id)
    (assert-registries-open!)
    (update-attack-check-registries-state! assoc-in [:cancel-checks check-id] check-fn))
  nil)

(defn should-cancel-attack?
  "Returns true if any registered check says the attack should be cancelled."
  [player-id attacker-id damage _damage-source]
  (boolean
    (some (fn [[_id check-fn]]
            (try
              (check-fn player-id attacker-id damage)
              (catch Exception e
                (log/warn "Attack cancel check failed:" (ex-message e))
                false)))
          (:cancel-checks (attack-check-registries-state-snapshot)))))

(defn register-attack-precheck-side-effect!
  "Register a side-effect callback executed during attack precheck.
  This is used when a platform event cancels attack before damage-stage handlers run.

  side-effect-fn signature:
  (fn [player-id attacker-id damage damage-source] -> any)
  "
  [effect-id side-effect-fn]
  (when-not (contains? (:precheck-side-effects (attack-check-registries-state-snapshot)) effect-id)
    (assert-registries-open!)
    (update-attack-check-registries-state! assoc-in [:precheck-side-effects effect-id] side-effect-fn))
  nil)

(defn run-attack-precheck-side-effects!
  "Run all registered precheck side-effects.
  Returns true if at least one callback completed without exception."
  [player-id attacker-id damage damage-source]
  (boolean
    (seq
      (keep (fn [[effect-id effect-fn]]
              (try
                 (when (effect-fn player-id attacker-id damage damage-source)
                   effect-id)
                (catch Exception e
                  (log/warn "Attack precheck side-effect failed:" effect-id (ex-message e))
                  nil)))
            (:precheck-side-effects (attack-check-registries-state-snapshot))))))
