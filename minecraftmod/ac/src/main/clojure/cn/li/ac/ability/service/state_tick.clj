(ns cn.li.ac.ability.service.state-tick
  "Server tick orchestration for player-state runtime updates."
  (:require [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn server-tick-player-in-session!
  [session-id uuid-str _sync-fn]
  (when-let [state (store/get-player-state* session-id uuid-str)]
    (let [cp-speed (evt/fire-calc-event! evt/CALC-CP-RECOVER-SPEED
                                         (cfg/cp-recover-speed)
                                         {:uuid uuid-str})
          ov-speed (evt/fire-calc-event! evt/CALC-OVERLOAD-RECOVER-SPEED
                                         (cfg/overload-recover-speed)
                                         {:uuid uuid-str})
          tick-result (command-rt/run-command-in-session! session-id
                                                          uuid-str
                                                          {:command :server-tick
                                                           :uuid uuid-str
                                                           :player-uuid uuid-str
                                                           :cp-speed cp-speed
                                                           :ol-speed ov-speed})
          all-events (vec (or (:events tick-result) []))]
      (doseq [e all-events] (evt/fire-ability-event! e))
      {:events all-events :state (or (:state tick-result) state)})))

(defn server-tick-player!
  [uuid-str sync-fn]
  (server-tick-player-in-session! (runtime-hooks/require-player-state-session-id "state-tick")
                                  uuid-str
                                  sync-fn))
