(ns cn.li.ac.ability.test.runtime-fixtures
  "Shared ability test runtime installation and store lifecycle helpers."
  (:require [cn.li.ac.ability.registry.event :as event-registry]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.runtime-container :as runtime-container]
            [cn.li.ac.ability.service.runtime-store :as store]))

(defn create-default-ability-runtime-container
  "Test factory for the standard ability runtime container."
  []
  (runtime-container/create-ability-runtime-container))

(defn install-default-ability-runtimes!
  "Install category/skill/event/lifecycle registries for ability tests."
  []
  (runtime-container/install-ability-runtime-container!
    (create-default-ability-runtime-container)))

(defn create-skill-registry-runtime
  "Test factory delegating to the production skill registry runtime."
  []
  (skill-registry/create-skill-registry-runtime))

(defn create-event-subscriber-runtime
  "Test factory delegating to the production event subscriber runtime."
  []
  (event-registry/create-event-subscriber-runtime))

(defn with-clean-store
  "Reset the ability store around a test body."
  [f]
  (cn.li.ac.test.support.player-state/with-test-player-state-owner
    (fn []
      (store/reset-store!)
      (try
        (f)
        (finally
          (store/reset-store!))))))

(defn with-default-ability-test-runtime
  "Install default ability runtimes and reset store for an isolated test."
  [f]
  (install-default-ability-runtimes!)
  (with-clean-store f))
