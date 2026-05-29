(ns cn.li.ac.ability.effects.interpreter
  "Effect interpreter — the Imperative Shell that executes effect plans.

  Receives the {:effects [...]} list produced by the reducer and
  dispatches each {:effect/type ...} plan to the appropriate handler.

  All handlers have side effects; none return meaningful values.
  The interpreter is the ONLY place in the ability system that talks
  to external systems (network, platform, persistence, events).

  Supported effect types:
    :network-send    — mark player dirty, triggering auto-sync to client
    :platform-call   — call a registered platform function via hooks registry
    :persist-state   — mark player dirty, triggering NBT save
    :fire-event      — fire an ability lifecycle event

  Unrecognised effect types are logged as warnings and skipped."
  (:require [cn.li.ac.ability.effects.network-handler    :as net]
            [cn.li.ac.ability.effects.platform-handler   :as platform]
            [cn.li.ac.ability.effects.persistence-handler :as persist]
            [cn.li.ac.ability.registry.event             :as evt]
            [cn.li.mcmod.util.log                        :as log]))

;; ============================================================================
;; Event handler
;; ============================================================================

(defn- execute-fire-event!
  "Handle :fire-event effect.

  Effect shape:
    {:effect/type  :fire-event
     :event        map}   ; passed directly to evt/fire-ability-event!"
  [{:keys [event]}]
  (when event
    (evt/fire-ability-event! event))
  nil)

;; ============================================================================
;; Main Dispatcher
;; ============================================================================

(defn execute-effect!
  "Dispatch and execute a single effect plan."
  [effect]
  (case (:effect/type effect)
    :network-send  (net/execute-network-send! effect)
    :platform-call (platform/execute-platform-call! effect)
    :persist-state (persist/execute-persist-state! effect)
    :fire-event    (execute-fire-event! effect)
    (log/warn "Unknown ability effect type" (:effect/type effect) effect)))

(defn execute-effects!
  "Execute a sequence of effect plans produced by the reducer.

  Called from the imperative shell after applying commands to the store.
  Each effect is executed in order; exceptions in one do not prevent
  subsequent effects from running."
  [effects]
  (doseq [effect effects]
    (try
      (execute-effect! effect)
      (catch Exception e
        (log/error "Effect execution failed" (:effect/type effect) e))))
  nil)

;; ============================================================================
;; Convenience: execute reducer result
;; ============================================================================

(defn execute-reducer-result!
  "Fire all events and execute all effects from a reducer result map.

  Accepts the map returned by reducer/apply-command or
  reducer/apply-commands:
    {:state ... :events [...] :effects [...]}

  Events are fired first (via evt/fire-ability-event!), then effects."
  [{:keys [events effects]}]
  (doseq [event events]
    (try
      (evt/fire-ability-event! event)
      (catch Exception e
        (log/error "Event firing failed" (:event/type event) e))))
  (execute-effects! effects)
  nil)