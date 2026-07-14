(ns cn.li.ac.ability.service.state-tick
  "Server tick orchestration for player-state runtime updates."
  (:require [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.reducer :as reducer]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- fire-recover-speed-event!
  "Extracted to top-level defn- so AOT emits one static class.
   Eliminates per-tick {:uuid uuid-str} map allocation inside doseq context."
  [event-type base-speed uuid-str]
  (evt/fire-calc-event! event-type base-speed {:uuid uuid-str}))

(defn server-tick-player-in-session!
  "Per-player per-tick state driver.
   Optimized: removed (vec) array clone — doseq handles any seq natively;
   added when guard so idle ticks skip iterator allocation entirely.
   Fully idle players (CP/overload at rest, no cooldowns, not developing)
   skip the :server-tick command dispatch entirely — zero commands, zero
   events, zero speed-calc allocations."
  [session-id uuid-str _sync-fn]
  (when-let [state (store/get-player-state* session-id uuid-str)]
    (when-not (reducer/server-tick-noop? state)
      (let [cp-speed (fire-recover-speed-event! evt/CALC-CP-RECOVER-SPEED
                                                (cfg/cp-recover-speed)
                                                uuid-str)
            ov-speed (fire-recover-speed-event! evt/CALC-OVERLOAD-RECOVER-SPEED
                                                (cfg/overload-recover-speed)
                                                uuid-str)
            tick-result (command-rt/run-command-in-session! session-id
                                                            uuid-str
                                                            {:command :server-tick
                                                             :uuid uuid-str
                                                             :player-uuid uuid-str
                                                             :cp-speed cp-speed
                                                             :ol-speed ov-speed})
            raw-events (:events tick-result)]
        (when raw-events
          (doseq [e raw-events] (evt/fire-ability-event! e)))
        {:events (or raw-events [])
         :state (or (:state tick-result) state)}))))

(defn server-tick-player!
  [uuid-str sync-fn]
  (server-tick-player-in-session! (runtime-hooks/require-player-state-session-id "state-tick")
                                  uuid-str
                                  sync-fn))
