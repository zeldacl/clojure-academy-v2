(ns cn.li.ac.ability.server.service.learning
  "Public aggregate API for learning services.

  Implementation is split into:
  - learning-conditions
  - learning-progression
  - learning-coordinator

  This namespace is intentionally retained as the stable learning-service
  surface while the implementation stays modular."
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
