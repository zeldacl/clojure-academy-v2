(ns cn.li.ac.ability.service.state-actions
  "Server-side ability state mutations with lifecycle-aware side effects.

  This namespace owns command/runtime mutations that need to update player-state
  and trigger lifecycle events in one place, instead of hand-editing runtime
  maps at each caller."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- resolve-session-id
  []
  (runtime-hooks/require-player-state-session-id "state-actions"))

(defn- ensure-state
  [uuid]
  (store/get-or-create-player-state! (resolve-session-id)
                                     uuid))

(defn- ensure-state-in-session
  [session-id uuid]
  (store/get-or-create-player-state! session-id uuid))

(declare set-level-in-session!
         learn-skill-in-session!
         learn-skills-in-session!
         set-skill-exp-in-session!
         unlearn-skill-in-session!
         recover-all-in-session!
         clear-cooldowns-in-session!
         reset-abilities-in-session!
         maxout-progression-in-session!
         change-category-in-session!)

(defn set-level!
  [uuid level]
  (set-level-in-session! (resolve-session-id)
                         uuid
                         level))

(defn set-level-in-session!
  [session-id uuid level]
  (let [state (ensure-state-in-session session-id uuid)
        old-level (int (get-in state [:ability-data :level] 1))
        result (command-rt/run-command-in-session! session-id
                                                   uuid
                                                   {:command :set-level
                                                    :level (int level)})
        new-level (int (get-in result [:state :ability-data :level] old-level))]
    {:changed? (not= old-level new-level)
     :old-level old-level
     :new-level new-level}))

(defn learn-skill!
  [uuid skill-id]
  (learn-skill-in-session! (resolve-session-id)
                           uuid
                           skill-id))

(defn learn-skill-in-session!
  [session-id uuid skill-id]
  (let [result (command-rt/run-command-in-session! session-id
                                                   uuid
                                                   {:command :learn-skill
                                                    :skill-id skill-id
                                                    :check-conditions? false})
        event (first (:events result))]
    {:data (get-in result [:state :ability-data])
     :event event}))

(defn learn-skills!
  [uuid skill-ids]
  (learn-skills-in-session! (resolve-session-id)
                            uuid
                            skill-ids))

(defn learn-skills-in-session!
  [session-id uuid skill-ids]
  (reduce (fn [result skill-id]
            (let [{:keys [event]} (learn-skill-in-session! session-id uuid skill-id)]
              (if event
                (-> result
                    (assoc :changed? true)
                    (update :learned-skill-ids conj skill-id))
                result)))
          {:changed? false
           :learned-skill-ids []}
          skill-ids))

(defn set-skill-exp!
  [uuid skill-id amount]
  (set-skill-exp-in-session! (resolve-session-id)
                             uuid
                             skill-id
                             amount))

(defn set-skill-exp-in-session!
  [session-id uuid skill-id amount]
  (let [result (command-rt/run-command-in-session! session-id
                                                   uuid
                                                   {:command :set-skill-exp
                                                    :skill-id skill-id
                                                    :amount amount})]
  {:changed? true
   :skill-id skill-id
   :exp (adata/get-skill-exp (get-in result [:state :ability-data]) skill-id)}))

(defn unlearn-skill!
  [uuid skill-id]
  (unlearn-skill-in-session! (resolve-session-id)
                             uuid
                             skill-id))

(defn unlearn-skill-in-session!
  [session-id uuid skill-id]
  (let [state (ensure-state-in-session session-id uuid)
        before [(:ability-data state) (:preset-data state)]
        result (command-rt/run-command-in-session! session-id
                                                   uuid
                                                   {:command :unlearn-skill
                                                    :skill-id skill-id})
        after [(get-in result [:state :ability-data])
               (get-in result [:state :preset-data])]]
    {:changed? (not= before after)
     :skill-id skill-id}))

(defn recover-all!
  [uuid]
  (recover-all-in-session! (resolve-session-id)
                           uuid))

(defn recover-all-in-session!
  [session-id uuid]
  (command-rt/run-command-in-session! session-id uuid {:command :recover-all})
  {:changed? true})

(defn clear-cooldowns!
  [uuid]
  (clear-cooldowns-in-session! (resolve-session-id)
                               uuid))

(defn clear-cooldowns-in-session!
  [session-id uuid]
  (command-rt/run-command-in-session! session-id uuid {:command :clear-all-cooldowns})
  {:changed? true})

(defn reset-abilities!
  [uuid]
  (reset-abilities-in-session! (resolve-session-id)
                               uuid))

(defn reset-abilities-in-session!
  [session-id uuid]
  (let [state (ensure-state-in-session session-id uuid)
        old-category (get-in state [:ability-data :category-id])]
    (command-rt/run-command-in-session! session-id uuid {:command :reset-abilities})
    {:changed? true
     :old-category old-category}))

(defn maxout-progression!
  [uuid skill-ids]
  (maxout-progression-in-session! (resolve-session-id)
                                  uuid
                                  skill-ids))

(defn maxout-progression-in-session!
  [session-id uuid skill-ids]
  (command-rt/run-commands-in-session!
   session-id
   uuid
   (vec (concat [{:command :set-level :level 5}]
                (map (fn [skill-id]
                       {:command :learn-skill
                        :skill-id skill-id
                        :check-conditions? false})
                     skill-ids)
                (map (fn [skill-id]
                       {:command :set-skill-exp
                        :skill-id skill-id
                        :amount 1.0})
                     skill-ids))))
  {:changed? true
   :skill-ids (vec skill-ids)})

(defn change-category!
  "Apply a category change through the command-runtime pipeline."
  [uuid new-category]
  (change-category-in-session! (resolve-session-id)
                               uuid
                               new-category))

(defn change-category-in-session!
  ([session-id uuid new-category]
   (let [state (ensure-state-in-session session-id uuid)
         old-category (get-in state [:ability-data :category-id])
         result (command-rt/run-command-in-session! session-id
                                                    uuid
                                                    {:command :change-category
                                                     :new-category new-category})
         changed? (not= old-category (get-in result [:state :ability-data :category-id]))]
     (when changed?
       {:ability-data (get-in result [:state :ability-data])
        :old-category old-category
        :new-category new-category}))))
