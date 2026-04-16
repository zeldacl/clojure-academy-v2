(ns cn.li.ac.ability.service.skill-effects
  "Common side-effect helpers for skills.

  Centralizes the boilerplate:
  - resource consumption via res/perform-resource
  - player-state updates
  - firing ability events"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.resource :as res]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.service.cooldown :as cd]
            [cn.li.ac.ability.event :as evt]))

(defn perform-resource!
  "Consume overload+cp from player's resource-data.
  Returns {:success? bool, :events events, :data new-resource-data}."
  ([player-id overload cp]
   (perform-resource! player-id overload cp false))
  ([player-id overload cp creative?]
   (if-let [state (ps/get-player-state player-id)]
     (let [{:keys [data success? events]} (res/perform-resource
                                          (:resource-data state)
                                          player-id
                                          (double overload)
                                          (double cp)
                                          (boolean creative?))]
       (when success?
         (ps/update-resource-data! player-id (constantly data))
         (doseq [e events] (evt/fire-ability-event! e)))
       {:success? (boolean success?)
        :events events
        :data data})
     {:success? false
      :events []
      :data nil})))

(defn add-skill-exp!
  "Add exp to a skill and fire produced events."
  ([player-id skill-id amount]
   (add-skill-exp! player-id skill-id amount 1.0))
  ([player-id skill-id amount exp-rate]
   (when-let [state (ps/get-player-state player-id)]
     (let [{:keys [data events]} (learning/add-skill-exp
                                  (:ability-data state)
                                  player-id
                                  skill-id
                                  (double amount)
                                  (double exp-rate))]
       (ps/update-ability-data! player-id (constantly data))
       (doseq [e events] (evt/fire-ability-event! e))
       {:data data :events events}))))

(defn set-main-cooldown!
  "Set main cooldown for ctrl-id (or skill-id)."
  [player-id ctrl-id cooldown-ticks]
  (ps/update-cooldown-data! player-id cd/set-main-cooldown ctrl-id (max 1 (int cooldown-ticks))))

