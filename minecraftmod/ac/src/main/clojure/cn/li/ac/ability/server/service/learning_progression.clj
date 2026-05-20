(ns cn.li.ac.ability.server.service.learning-progression
  "Progression calculations and mutations for ability leveling."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.registry :as skill]
            [cn.li.ac.ability.registry.category :as cat]
            [cn.li.ac.ability.util.level-formula :as level-formula]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.registry.event :as evt]))

(defn level-up-threshold
  "Number of exp points needed to advance to next level.
  Counts controllable skills at the current level with mastery multiplier.
  When all skills at the level are mastered (exp >= 1.0), threshold is halved."
  [cat-id ability-data]
  (let [level       (:level ability-data)
        skills      (skill/get-controllable-skills-at-level cat-id level)
        skill-count (count skills)
        all-mastered? (and (pos? skill-count)
                          (every? #(>= (adata/get-skill-exp ability-data (:id %)) 1.0)
                                  skills))
        cat-rate    (cat/get-prog-incr-rate cat-id)
        global-rate (cfg/prog-incr-rate)]
    (level-formula/level-up-threshold skill-count all-mastered? cat-rate global-rate)))

(defn can-level-up?
  [ability-data]
  (let [level (:level ability-data)
        cat   (:category-id ability-data)]
    (and (< level (cfg/max-level))
         (some? cat)
         (>= (:level-progress ability-data)
             (level-up-threshold cat ability-data)))))

(defn add-skill-exp
  "Add exp to a skill. Also accumulates level progress.
  Returns {:data updated-ability-data :events [...]}.

  exp-incr-speed is the skill-specific multiplier (from skill spec)."
  [ability-data uuid skill-id raw-amount exp-incr-speed]
  (let [scaled-amount (* (double raw-amount)
                         (double (or exp-incr-speed 1.0))
                         (cfg/prog-incr-rate))
        {:keys [data delta]} (adata/add-skill-exp ability-data skill-id scaled-amount)
        data2 (adata/add-level-progress data delta)
        exp-added-event {:event/type evt/EVT-SKILL-EXP-ADDED
                         :event/side :server
                         :uuid uuid :skill-id skill-id :amount delta}
        exp-changed-event {:event/type evt/EVT-SKILL-EXP-CHANGED
                           :event/side :server
                           :uuid uuid :skill-id skill-id
                           :new-exp (adata/get-skill-exp data2 skill-id)}]
    {:data   data2
     :events (if (pos? delta) [exp-added-event exp-changed-event] [])}))

(defn level-up
  "Execute level-up if conditions are met.
  Returns {:data updated-ability-data :event level-change-event | nil}."
  [ability-data uuid]
  (if-not (can-level-up? ability-data)
    {:data ability-data :event nil}
    (let [old-level (:level ability-data)
          new-level (inc old-level)
          data2     (adata/set-level ability-data new-level)]
      {:data  data2
       :event (evt/make-level-change-event uuid old-level new-level)})))
