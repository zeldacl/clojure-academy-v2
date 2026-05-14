(ns cn.li.ac.ability.server.service.learning-coordinator
  "Coordinator operations for learning workflow."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.registry.event :as evt]))

(defn learn-skill
  "Learn a skill (unchecked – caller must verify conditions first).
  Returns {:data updated-ability-data :event skill-learn-event}."
  [ability-data uuid skill-id]
  (if (adata/is-learned? ability-data skill-id)
    {:data ability-data :event nil}
    {:data  (adata/learn-skill ability-data skill-id)
     :event (evt/make-skill-learn-event uuid skill-id)}))
