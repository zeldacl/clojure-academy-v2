(ns cn.li.ac.ability.effects.persistence-handler
  "Effect handler for :persist-state effects.

  Marks the player dirty so the platform NBT save cycle serializes
  the updated state.

  Effect shape:
    {:effect/type  :persist-state
     :player-uuid  string-uuid
     :domain       keyword}"
  (:require 
            [cn.li.ac.ability.service.player-state-dirty :as ps-dirty]
[cn.li.mcmod.util.log :as log]))

(defn execute-persist-state!
  [{:keys [player-uuid domain]}]
  (when player-uuid
    (ps-dirty/mark-dirty! player-uuid)
    (log/debug "persist-state effect queued save" player-uuid domain))
  nil)

