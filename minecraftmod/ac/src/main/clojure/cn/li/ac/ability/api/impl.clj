(ns cn.li.ac.ability.api.impl
  "Default public implementation for the new ability API facade."
  (:require 
            [cn.li.ac.ability.service.state-tick :as ps-tick]
[cn.li.ac.ability.service.runtime-store :as store]
[cn.li.ac.ability.api.protocol :as proto]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill]
[cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.context-dispatcher :as dispatcher]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- default-session-id-provider
  []
  (runtime-hooks/require-player-state-session-id "Ability API state access"))

(defn- require-session-id
  [session-id-provider]
  (or (when (fn? session-id-provider)
        (session-id-provider))
      (default-session-id-provider)))

(defrecord AbilitySystemImpl [session-id-provider]
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
    (store/get-player-state* (require-session-id session-id-provider) player-uuid))
  (get-or-create-player-state! [_this player-uuid]
    (store/get-or-create-player-state! (require-session-id session-id-provider) player-uuid))
  (set-player-state! [_this player-uuid state]
    (store/set-player-state!* (require-session-id session-id-provider) player-uuid state))
  (update-player-state! [_this player-uuid f args]
    (store/update-player-state!* (require-session-id session-id-provider) player-uuid #(apply f % args)))
  (mark-dirty! [_this player-uuid]
    (store/mark-player-dirty! (require-session-id session-id-provider) player-uuid))
  (mark-clean! [_this player-uuid]
    (store/clear-dirty! (store/get-store)
                       (require-session-id session-id-provider)
                       player-uuid))
  (dirty? [_this player-uuid]
    (boolean (:dirty? (store/get-player-state* (require-session-id session-id-provider) player-uuid))))
  (server-tick-player! [_this player-uuid sync-fn]
    (ps-tick/server-tick-player-in-session! (require-session-id session-id-provider) player-uuid sync-fn))
  (remove-player-state! [_this player-uuid]
    (store/remove-player-state!* (require-session-id session-id-provider) player-uuid))

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

(defn ability-system
  "Create a fresh ability system facade instance.

  The facade is stateless and delegates to explicit runtime components,
  so callers should wire lifecycle through runtime-bridge instead of relying
  on an implicit process-global singleton."
  ([]
   (ability-system {}))
  ([{:keys [session-id-provider]
     :or {session-id-provider default-session-id-provider}}]
   (->AbilitySystemImpl session-id-provider)))



