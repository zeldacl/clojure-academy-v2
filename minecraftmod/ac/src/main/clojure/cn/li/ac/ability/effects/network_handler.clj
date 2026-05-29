(ns cn.li.ac.ability.effects.network-handler
  "Effect handler for :network-send effects.

  Marks a player dirty so the platform auto-sync loop pushes the
  updated state to the client on the next tick.

  Effect shape:
    {:effect/type  :network-send
     :player-uuid  string-uuid
     :channel      keyword
     :payload      map}"
  (:require [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.mcmod.util.log :as log]))

(defn execute-network-send!
  [{:keys [player-uuid channel]}]
  (when player-uuid
    (ps/mark-dirty! player-uuid)
    (log/debug "network-send effect queued sync" player-uuid channel))
  nil)