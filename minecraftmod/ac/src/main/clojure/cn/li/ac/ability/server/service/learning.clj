(ns cn.li.ac.ability.server.service.learning
  "Compatibility facade for learning services.

  Implementation was split into:
  - learning-conditions
  - learning-progression
  - learning-coordinator"
  (:require [cn.li.ac.ability.server.service.learning-conditions :as conditions]
            [cn.li.ac.ability.server.service.learning-progression :as progression]
            [cn.li.ac.ability.server.service.learning-coordinator :as coordinator]))

(def check-level-condition conditions/check-level-condition)
(def check-dep-condition conditions/check-dep-condition)
(def check-developer-type-condition conditions/check-developer-type-condition)
(def check-all-conditions conditions/check-all-conditions)
(def can-learn? conditions/can-learn?)

(def learn-skill coordinator/learn-skill)

(def level-up-threshold progression/level-up-threshold)
(def can-level-up? progression/can-level-up?)
(def add-skill-exp progression/add-skill-exp)
(def level-up progression/level-up)
