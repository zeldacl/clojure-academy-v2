(ns cn.li.ac.ability.damage-handler
  "Damage handler registration and management for skills.

  Skills can register damage handlers to intercept and modify incoming damage.
  This module manages the registration and provides utilities for common patterns.

  No Minecraft imports."
  (:require [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.state.context :as ctx]
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

(defonce ^:private attack-cancel-checks
  ;; keyword → (fn [player-id attacker-id damage] → boolean)
  (atom {}))

(defn register-attack-cancel-check!
  "Register a predicate that decides whether an attack should be cancelled.
  Content skills call this at load time."
  [check-id check-fn]
  (swap! attack-cancel-checks assoc check-id check-fn)
  nil)

(defn should-cancel-attack?
  "Returns true if any registered check says the attack should be cancelled."
  [player-id attacker-id damage damage-source]
  (boolean
    (some (fn [[_id check-fn]]
            (try
              (check-fn player-id attacker-id damage)
              (catch Exception e
                (log/warn "Attack cancel check failed:" (ex-message e))
                false)))
          @attack-cancel-checks)))
