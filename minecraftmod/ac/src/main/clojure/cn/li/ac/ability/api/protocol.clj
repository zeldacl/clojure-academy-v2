(ns cn.li.ac.ability.api.protocol
  "Public ability system contracts for the refactored AC architecture.")

(defprotocol IAbilityRegistry
  (register-category! [this category-spec])
  (register-skill! [this skill-spec])
  (get-category [this category-id])
  (get-skill [this skill-id])
  (list-categories [this])
  (list-skills [this])
  (get-skills-for-category [this category-id])
  (get-skill-by-controllable [this category-id ctrl-id]))

(defprotocol IAbilityState
  (get-player-state [this player-uuid])
  (get-or-create-player-state! [this player-uuid])
  (set-player-state! [this player-uuid state])
  (update-player-state! [this player-uuid f args])
  (mark-dirty! [this player-uuid])
  (mark-clean! [this player-uuid])
  (dirty? [this player-uuid])
  (server-tick-player! [this player-uuid sync-fn])
  (remove-player-state! [this player-uuid]))

(defprotocol IAbilityDispatcher
  (start-context! [this player-uuid skill-id])
  (start-server-context! [this player-uuid skill-id client-id])
  (dispatch-skill-event! [this skill-id callback-key event])
  (terminate-context! [this ctx-id send-terminated-fn])
  (active-contexts [this] [this player-uuid])
  (send-context-message! [this ctx-id direction channel payload]))
