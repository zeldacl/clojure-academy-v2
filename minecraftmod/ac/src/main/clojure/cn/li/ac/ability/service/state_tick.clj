(ns cn.li.ac.ability.service.state-tick
  "Server tick orchestration for player-state runtime updates."
  (:require [cn.li.ac.ability.model.develop :as dev]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.rules.develop-rules :as develop-rules]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.reducer :as reducer]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn server-tick-player-in-session!
  [session-id uuid-str sync-fn]
  (when-let [state (store/get-player-state* session-id uuid-str)]
    (let [cp-speed (evt/fire-calc-event! evt/CALC-CP-RECOVER-SPEED
                                         (cfg/cp-recover-speed)
                                         {:uuid uuid-str})
          ov-speed (evt/fire-calc-event! evt/CALC-OVERLOAD-RECOVER-SPEED
                                         (cfg/overload-recover-speed)
                                         {:uuid uuid-str})
          tick-result (reducer/apply-command state {:command :server-tick
                                                    :uuid uuid-str
                                                    :player-uuid uuid-str
                                                    :cp-speed cp-speed
                                                    :ol-speed ov-speed})
          ticked-state (or (:state tick-result) state)
          {:keys [develop-data]} ticked-state
          dev-result (when (and develop-data (dev/developing? develop-data))
                       (develop-rules/tick-develop develop-data))
          new-dev (if dev-result (:develop-data dev-result) develop-data)
          completion (when (and dev-result (:completed? dev-result))
                       (develop-rules/apply-completion
                        new-dev
                        (:ability-data ticked-state)
                        (:resource-data ticked-state)
                        uuid-str))
          final-ability (if completion (:ability-data completion) (:ability-data ticked-state))
          final-resource (if completion (:resource-data completion) (:resource-data ticked-state))
          final-dev (if completion (:develop-data completion) new-dev)
          all-events (vec (or (:events completion) []))]
      (store/update-player-state!* session-id
                                   uuid-str
                                   #(assoc %
                                           :ability-data final-ability
                                           :resource-data final-resource
                                           :cooldown-data (:cooldown-data ticked-state)
                                           :develop-data (or final-dev (dev/new-develop-data))))
      (doseq [e all-events] (evt/fire-ability-event! e))
      (when (and sync-fn (seq all-events))
        (store/mark-player-dirty! session-id uuid-str))
      {:events all-events})))

(defn server-tick-player!
  [uuid-str sync-fn]
  (server-tick-player-in-session! (runtime-hooks/require-player-state-session-id "state-tick")
                                  uuid-str
                                  sync-fn))
