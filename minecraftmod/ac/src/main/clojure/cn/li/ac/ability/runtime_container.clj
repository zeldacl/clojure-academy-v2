(ns cn.li.ac.ability.runtime-container
  "Composition-root container for ability runtime components.

  This namespace centralizes runtime creation/installation so loader bridges
  can wire explicit runtimes without duplicating fallback logic."
  (:require [cn.li.ac.ability.registry.category :as category-registry]
            [cn.li.ac.ability.registry.event :as event-registry]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.passive :as passive]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.server.handlers.common :as handler-common]
            [cn.li.ac.ability.service.state-actions :as state-actions]
            [cn.li.ac.ability.service.state-accessors :as state-accessors]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.service.context-manager :as context-manager]
            [cn.li.ac.ability.service.context-state :as context-state]
            [cn.li.ac.ability.service.context-dispatcher :as context-dispatcher]
            [cn.li.ac.ability.service.state-tick :as state-tick]
            [cn.li.ac.ability.effects.interpreter :as effects-interpreter]
            [cn.li.ac.ability.spi-lifecycle :as lifecycle-registry]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn default-ability-runtime-container
  []
  {:category-registry-runtime (category-registry/create-category-registry-runtime)
   :event-subscriber-runtime (event-registry/create-event-subscriber-runtime)
   :skill-registry-runtime (skill-registry/create-skill-registry-runtime)
    :dispatcher-runtime (context-dispatcher/create-dispatcher-runtime)
    :lifecycle-registry-runtime (lifecycle-registry/create-lifecycle-registry-runtime)
    :session-id-resolver (fn [] (runtime-hooks/player-state-session-id))
    :server-session-id-resolver (fn [] (runtime-hooks/player-state-server-session-id))
    :owner-resolver (fn [] (runtime-hooks/current-player-state-owner))})

(defn create-ability-runtime-container
  ([]
   (default-ability-runtime-container))
  ([overrides]
   (merge (default-ability-runtime-container)
          (or overrides {}))))

(defn install-ability-runtime-container!
  [{:keys [category-registry-runtime
           event-subscriber-runtime
           skill-registry-runtime
           dispatcher-runtime
           session-id-resolver
           server-session-id-resolver
           owner-resolver
           lifecycle-registry-runtime] :as container}]
  (category-registry/install-category-registry-runtime! category-registry-runtime)
  (event-registry/install-event-subscriber-runtime! event-subscriber-runtime)
  (skill-registry/install-skill-registry-runtime! skill-registry-runtime)
  (context-dispatcher/install-dispatcher-runtime! dispatcher-runtime)
  (lifecycle-registry/install-lifecycle-registry-runtime! lifecycle-registry-runtime)
  (command-runtime/install-session-runtime!
    {:session-id-resolver session-id-resolver
     :owner-resolver owner-resolver})
  (context-manager/install-session-runtime!
    {:server-session-id-resolver server-session-id-resolver})
  (context-state/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (state-actions/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (handler-common/install-session-runtime!
    {:server-session-id-resolver server-session-id-resolver
     :owner-resolver owner-resolver})
  (skill-effects/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (passive/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (state-accessors/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (state-tick/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  (effects-interpreter/install-session-runtime!
    {:session-id-resolver session-id-resolver})
  container)
