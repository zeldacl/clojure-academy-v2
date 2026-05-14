(ns cn.li.ac.ability.service.player-state-tick
  "Server tick orchestration for player-state runtime updates."
  (:require [cn.li.ac.ability.model.develop :as dev]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.server.service.cooldown :as svc-cd]
            [cn.li.ac.ability.server.service.develop :as svc-dev]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.player-state-core :as core]
            [cn.li.ac.ability.service.player-state-dirty :as dirty]))

(defn server-tick-player!
  [uuid-str sync-fn]
  (when-let [state (core/get-player-state uuid-str)]
    (let [{:keys [resource-data cooldown-data develop-data]} state
          {:keys [data events]} (svc-res/server-tick resource-data uuid-str)
          new-cd (svc-cd/tick-cooldowns cooldown-data)
          dev-result (when (and develop-data (dev/developing? develop-data))
                       (svc-dev/tick-develop develop-data))
          new-dev    (if dev-result (:develop-data dev-result) develop-data)
          completion (when (and dev-result (:completed? dev-result))
                       (svc-dev/apply-completion
                        new-dev
                        (:ability-data state)
                        data
                        uuid-str))
          final-ability  (if completion (:ability-data completion) (:ability-data state))
          final-resource (if completion (:resource-data completion) data)
          final-dev      (if completion (:develop-data completion) new-dev)
          all-events     (into (vec events)
                               (when completion (:events completion)))]
      (core/update-player-state! uuid-str
                                 assoc
                                 :ability-data  final-ability
                                 :resource-data final-resource
                                 :cooldown-data new-cd
                                 :develop-data  (or final-dev (dev/new-develop-data)))
      (doseq [e all-events] (evt/fire-ability-event! e))
      (when (and sync-fn (seq all-events))
        (dirty/mark-dirty! uuid-str))
      {:events all-events})))
