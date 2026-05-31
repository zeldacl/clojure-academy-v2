(ns cn.li.ac.ability.service.state-actions
  "Server-side ability state mutations with lifecycle-aware side effects.

  This namespace owns command/runtime mutations that need to update player-state
  and trigger lifecycle events in one place, instead of hand-editing runtime
  maps at each caller."
  (:require [cn.li.ac.ability.service.state-accessors :as state-accessors]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.ac.ability.model.develop :as ddata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- ensure-state
  [uuid]
  (store/get-or-create-player-state! (runtime-hooks/require-player-state-session-id "state-actions")
                                     uuid))

(defn- ensure-state-in-session
  [session-id uuid]
  (store/get-or-create-player-state! session-id uuid))

(defn- replace-runtime-state!
  [uuid state]
  (let [session-id (runtime-hooks/require-player-state-session-id "state-actions")]
    (store/set-player-state!* session-id uuid state)
    (store/mark-player-dirty! session-id uuid))
  state)

(defn- replace-runtime-state-in-session!
  [session-id uuid state]
  (store/set-player-state!* session-id uuid state)
  (store/mark-player-dirty! session-id uuid)
  state)

(defn- update-runtime-state!
  [uuid f]
  (store/update-player-state!* (runtime-hooks/require-player-state-session-id "state-actions")
                               uuid
                               f))

(defn- update-runtime-state-in-session!
  [session-id uuid f]
  (store/update-player-state!* session-id uuid f))

(defn- clear-skill-from-presets
  [preset-data skill-id]
  (if-let [controllable (skill-query/controllable-key skill-id)]
    (reduce-kv (fn [data [preset-idx key-idx] slot]
                 (if (= slot controllable)
                   (pdata/set-slot data preset-idx key-idx nil)
                   data))
               preset-data
               (:slots preset-data))
    preset-data))

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
  (set-level-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                         uuid
                         level))

(defn set-level-in-session!
  [session-id uuid level]
  (let [state (ensure-state-in-session session-id uuid)
        old-level (int (get-in state [:ability-data :level] 1))
        new-level (int level)]
    (when (not= old-level new-level)
      (state-accessors/update-ability-data-in-session! session-id uuid adata/set-level new-level)
      (evt/fire-ability-event! (evt/make-level-change-event uuid old-level new-level)))
    {:changed? (not= old-level new-level)
     :old-level old-level
     :new-level new-level}))

(defn learn-skill!
  [uuid skill-id]
  (learn-skill-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
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
  (learn-skills-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
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
  (set-skill-exp-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                             uuid
                             skill-id
                             amount))

(defn set-skill-exp-in-session!
  [session-id uuid skill-id amount]
  (state-accessors/update-ability-data-in-session! session-id uuid adata/set-skill-exp skill-id amount)
  {:changed? true
   :skill-id skill-id
   :exp (adata/get-skill-exp (:ability-data (ensure-state-in-session session-id uuid)) skill-id)})

(defn unlearn-skill!
  [uuid skill-id]
  (unlearn-skill-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                             uuid
                             skill-id))

(defn unlearn-skill-in-session!
  [session-id uuid skill-id]
  (let [state (ensure-state-in-session session-id uuid)
        updated-state (-> state
                          (update :ability-data
                                  (fn [ability-data]
                                    (-> ability-data
                                        (update :learned-skills disj skill-id)
                                        (update :skill-exps dissoc skill-id))))
                          (update :preset-data clear-skill-from-presets skill-id))]
    (when (not= [(:ability-data state) (:preset-data state)]
                [(:ability-data updated-state) (:preset-data updated-state)])
      (replace-runtime-state-in-session! session-id uuid updated-state))
    {:changed? (not= [(:ability-data state) (:preset-data state)]
                     [(:ability-data updated-state) (:preset-data updated-state)])
     :skill-id skill-id}))

(defn recover-all!
  [uuid]
  (recover-all-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                           uuid))

(defn recover-all-in-session!
  [session-id uuid]
  (state-accessors/update-resource-data-in-session! session-id uuid rdata/recover-all)
  {:changed? true})

(defn clear-cooldowns!
  [uuid]
  (clear-cooldowns-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                               uuid))

(defn clear-cooldowns-in-session!
  [session-id uuid]
  (state-accessors/update-cooldown-data-in-session! session-id uuid (constantly (cdata/new-cooldown-data)))
  {:changed? true})

(defn reset-abilities!
  [uuid]
  (reset-abilities-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                               uuid))

(defn reset-abilities-in-session!
  [session-id uuid]
  (let [state (ensure-state-in-session session-id uuid)
        old-category (get-in state [:ability-data :category-id])
        updated-state (assoc state
                             :ability-data (adata/new-ability-data)
                             :resource-data (rdata/new-resource-data)
                             :cooldown-data (cdata/new-cooldown-data)
                             :preset-data (pdata/new-preset-data)
                             :develop-data (ddata/new-develop-data))]
    (replace-runtime-state-in-session! session-id uuid updated-state)
    (when old-category
      (evt/fire-ability-event! (evt/make-category-change-event uuid old-category nil)))
    {:changed? true
     :old-category old-category}))

(defn maxout-progression!
  [uuid skill-ids]
  (maxout-progression-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                                  uuid
                                  skill-ids))

(defn maxout-progression-in-session!
  [session-id uuid skill-ids]
  (set-level-in-session! session-id uuid 5)
  (learn-skills-in-session! session-id uuid skill-ids)
  (doseq [skill-id skill-ids]
    (set-skill-exp-in-session! session-id uuid skill-id 1.0))
  {:changed? true
   :skill-ids (vec skill-ids)})

(defn change-category!
  "Apply a category change, clear preset slots, and fire the category-change event.

  The optional `ability-update-fn` can extend the mutation with related ability
  changes such as a forced level adjustment."
  ([uuid new-category]
   (change-category! uuid new-category #(adata/set-category % new-category)))
  ([uuid new-category ability-update-fn]
   (change-category-in-session! (runtime-hooks/require-player-state-session-id "state-actions")
                                uuid
                                new-category
                                ability-update-fn)))

(defn change-category-in-session!
  ([session-id uuid new-category]
   (change-category-in-session! session-id uuid new-category #(adata/set-category % new-category)))
  ([session-id uuid new-category ability-update-fn]
   (let [state (ensure-state-in-session session-id uuid)
         old-category (get-in state [:ability-data :category-id])]
     (when (not= old-category new-category)
       (let [updated-ability (ability-update-fn (:ability-data state))]
         (update-runtime-state-in-session!
          session-id
          uuid
          (fn [player-state]
            (-> player-state
                (assoc :ability-data updated-ability)
                (update :preset-data pdata/clear-slots))))
         (evt/fire-ability-event!
          (evt/make-category-change-event uuid old-category new-category))
         {:ability-data updated-ability
          :old-category old-category
          :new-category new-category})))))
