(ns cn.li.ac.ability.api.impl
  "Default public implementation for the new ability API facade."
  (:require [cn.li.ac.ability.api.protocol :as proto]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.player-state :as player-state]
            [cn.li.ac.ability.service.dispatcher :as dispatcher]))

(defrecord AbilitySystemImpl []
  proto/IAbilityRegistry
  (register-category! [_this category-spec]
    (category/register-category! category-spec))
  (register-skill! [_this skill-spec]
    (skill/register-skill! skill-spec))
  (get-category [_this category-id]
    (category/get-category category-id))
  (get-skill [_this skill-id]
    (skill/get-skill skill-id))
  (list-categories [_this]
    (category/get-all-categories))
  (list-skills [_this]
    (skill-query/list-skills))
  (get-skills-for-category [_this category-id]
    (skill-query/get-skills-for-category category-id))
  (get-skill-by-controllable [_this category-id ctrl-id]
    (skill-query/get-skill-by-controllable category-id ctrl-id))

  proto/IAbilityState
  (get-player-state [_this player-uuid]
    (player-state/get-player-state player-uuid))
  (get-or-create-player-state! [_this player-uuid]
    (player-state/get-or-create-player-state! player-uuid))
  (set-player-state! [_this player-uuid state]
    (player-state/set-player-state! player-uuid state))
  (update-player-state! [_this player-uuid f args]
    (apply player-state/update-player-state! player-uuid f args))
  (mark-dirty! [_this player-uuid]
    (player-state/mark-dirty! player-uuid))
  (mark-clean! [_this player-uuid]
    (player-state/mark-clean! player-uuid))
  (dirty? [_this player-uuid]
    (player-state/dirty? player-uuid))
  (server-tick-player! [_this player-uuid sync-fn]
    (player-state/server-tick-player! player-uuid sync-fn))
  (remove-player-state! [_this player-uuid]
    (player-state/remove-player-state! player-uuid))

  proto/IAbilityDispatcher
  (start-context! [_this player-uuid skill-id]
    (dispatcher/start-context! player-uuid skill-id))
  (start-server-context! [_this player-uuid skill-id client-id]
    (dispatcher/start-server-context! player-uuid skill-id client-id))
  (dispatch-skill-event! [_this skill-id callback-key event]
    (dispatcher/dispatch-skill-event! skill-id callback-key event))
  (terminate-context! [_this ctx-id send-terminated-fn]
    (dispatcher/terminate-context! ctx-id send-terminated-fn))
  (active-contexts [_this]
    (dispatcher/active-contexts))
  (active-contexts [_this player-uuid]
    (dispatcher/active-contexts player-uuid))
  (send-context-message! [_this ctx-id direction channel payload]
    (dispatcher/send-context-message! ctx-id direction channel payload)))

(def ^:private ability-system-lock
  (Object.))

(def ^:private ^:dynamic *default-ability-system*
  nil)

(defn ability-system
  []
  (or (var-get #'*default-ability-system*)
      (locking ability-system-lock
        (or (var-get #'*default-ability-system*)
            (let [system (->AbilitySystemImpl)]
              (alter-var-root #'*default-ability-system* (constantly system))
              system)))))
