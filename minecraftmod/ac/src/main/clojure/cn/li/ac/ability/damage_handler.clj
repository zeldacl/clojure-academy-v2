(ns cn.li.ac.ability.damage-handler
  "Damage handler registration and management for skills.

  Skills can register damage handlers to intercept and modify incoming damage.
  This module manages the registration and provides utilities for common patterns.

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.util.toggle :as toggle]
            [cn.li.ac.ability.context :as ctx]
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

(defn init-damage-handlers!
  "Initialize all skill damage handlers.
  Should be called after damage interception system is installed."
  []
  (try
    ;; Register VecDeviation damage handler
    (when-let [vec-deviation-ns (find-ns 'cn.li.ac.content.ability.vecmanip.vec-deviation)]
      (when-let [reduce-damage-fn (ns-resolve vec-deviation-ns 'reduce-damage)]
        (register-toggle-damage-handler!
         :vec-deviation-damage
         :vec-deviation
         (fn [player-id _attacker-id damage _damage-source]
           (let [reduced-damage (reduce-damage-fn player-id damage)]
             [reduced-damage {:handler :vec-deviation}]))
         50)))  ; Priority 50 - early

    ;; Register VecReflection damage handler
    (when-let [vec-reflection-ns (find-ns 'cn.li.ac.content.ability.vecmanip.vec-reflection)]
      (when-let [reflect-damage-fn (ns-resolve vec-reflection-ns 'reflect-damage)]
        (register-toggle-damage-handler!
         :vec-reflection-damage
         :vec-reflection
         (fn [player-id attacker-id damage _damage-source]
           (let [[_performed reduced-damage] (reflect-damage-fn player-id attacker-id damage)]
             [reduced-damage {:handler :vec-reflection}]))
         60)))  ; Priority 60 - after VecDeviation

    (log/info "Damage handlers initialized")
    (catch Exception e
      (log/warn "Failed to initialize damage handlers:" (ex-message e)))))
