(ns cn.li.ac.ability.service.command-runtime
  "Imperative shell bridge for reducer command execution.

  This namespace is the runtime entry for command-based state transitions:
  1) load current player state
  2) apply reducer command(s)
  3) persist resulting state
  4) execute emitted events/effects"
  (:require 
            [cn.li.ac.ability.service.player-state-dirty :as ps-dirty]
[cn.li.ac.ability.service.player-state-core :as ps-core]
[cn.li.ac.ability.effects.interpreter :as interpreter]            [cn.li.ac.ability.service.reducer :as reducer]))

(defn- ensure-command-owner-fields
  [uuid command]
  (-> command
      (assoc :uuid uuid)
      (assoc :player-uuid uuid)))

(defn run-command!
  "Execute one reducer command for a player and apply side effects.

  Returns reducer result map."
  ([uuid command]
   (run-command! uuid command {}))
  ([uuid command {:keys [mark-dirty?]
                  :or {mark-dirty? true}}]
   (let [state (or (ps-core/get-player-state uuid)
                   (ps-core/get-or-create-player-state! uuid))
         result (reducer/apply-command state (ensure-command-owner-fields uuid command))
         next-state (:state result)]
     (when (and next-state (not= next-state state))
       (ps-core/set-player-state! uuid next-state)
       (when mark-dirty?
         (ps-dirty/mark-dirty! uuid)))
     (interpreter/execute-reducer-result! result)
     result)))

(defn run-commands!
  "Execute multiple reducer commands in order for one player.

  Returns aggregated reducer result map."
  ([uuid commands]
   (run-commands! uuid commands {}))
  ([uuid commands {:keys [mark-dirty?]
                   :or {mark-dirty? true}}]
   (let [state (or (ps-core/get-player-state uuid)
                   (ps-core/get-or-create-player-state! uuid))
         normalized (mapv #(ensure-command-owner-fields uuid %) commands)
         result (reducer/apply-commands state normalized)
         next-state (:state result)]
     (when (and next-state (not= next-state state))
       (ps-core/set-player-state! uuid next-state)
       (when mark-dirty?
         (ps-dirty/mark-dirty! uuid)))
     (interpreter/execute-reducer-result! result)
     result)))



