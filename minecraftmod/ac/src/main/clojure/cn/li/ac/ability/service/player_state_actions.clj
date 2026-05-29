(ns cn.li.ac.ability.service.player-state-actions
  "Server-side ability state mutations with lifecycle-aware side effects.

  This namespace owns command/runtime mutations that need to update player-state
  and trigger lifecycle events in one place, instead of hand-editing runtime
  maps at each caller."
  (:require [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.cooldown :as cdata]
            [cn.li.ac.ability.model.develop :as ddata]
            [cn.li.ac.ability.model.preset :as pdata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.player-state :as ps]))

(defn- ensure-state
  [uuid]
  (or (ps/get-player-state uuid)
      (ps/get-or-create-player-state! uuid)))

(defn- replace-runtime-state!
  [uuid state]
  (ps/set-player-state! uuid state)
  (ps/mark-dirty! uuid)
  state)

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

(defn set-level!
  [uuid level]
  (let [state (ensure-state uuid)
        old-level (int (get-in state [:ability-data :level] 1))
        new-level (int level)]
    (when (not= old-level new-level)
      (ps/update-ability-data! uuid adata/set-level new-level)
      (evt/fire-ability-event! (evt/make-level-change-event uuid old-level new-level)))
    {:changed? (not= old-level new-level)
     :old-level old-level
     :new-level new-level}))

(defn learn-skill!
  [uuid skill-id]
  (let [result (command-rt/run-command! uuid {:command :learn-skill
                                              :skill-id skill-id
                                              :check-conditions? false})
        event (first (:events result))]
    {:data (get-in result [:state :ability-data])
     :event event}))

(defn learn-skills!
  [uuid skill-ids]
  (reduce (fn [result skill-id]
            (let [{:keys [event]} (learn-skill! uuid skill-id)]
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
  (ps/update-ability-data! uuid adata/set-skill-exp skill-id amount)
  {:changed? true
   :skill-id skill-id
   :exp (adata/get-skill-exp (:ability-data (ensure-state uuid)) skill-id)})

(defn unlearn-skill!
  [uuid skill-id]
  (let [state (ensure-state uuid)
        updated-state (-> state
                          (update :ability-data
                                  (fn [ability-data]
                                    (-> ability-data
                                        (update :learned-skills disj skill-id)
                                        (update :skill-exps dissoc skill-id))))
                          (update :preset-data clear-skill-from-presets skill-id))]
    (when (not= [(:ability-data state) (:preset-data state)]
                [(:ability-data updated-state) (:preset-data updated-state)])
      (replace-runtime-state! uuid updated-state))
    {:changed? (not= [(:ability-data state) (:preset-data state)]
                     [(:ability-data updated-state) (:preset-data updated-state)])
     :skill-id skill-id}))

(defn recover-all!
  [uuid]
  (ps/update-resource-data! uuid rdata/recover-all)
  {:changed? true})

(defn clear-cooldowns!
  [uuid]
  (ps/update-cooldown-data! uuid (constantly (cdata/new-cooldown-data)))
  {:changed? true})

(defn reset-abilities!
  [uuid]
  (let [state (ensure-state uuid)
        old-category (get-in state [:ability-data :category-id])
        updated-state (assoc state
                             :ability-data (adata/new-ability-data)
                             :resource-data (rdata/new-resource-data)
                             :cooldown-data (cdata/new-cooldown-data)
                             :preset-data (pdata/new-preset-data)
                             :develop-data (ddata/new-develop-data))]
    (replace-runtime-state! uuid updated-state)
    (when old-category
      (evt/fire-ability-event! (evt/make-category-change-event uuid old-category nil)))
    {:changed? true
     :old-category old-category}))

(defn maxout-progression!
  [uuid skill-ids]
  (set-level! uuid 5)
  (learn-skills! uuid skill-ids)
  (doseq [skill-id skill-ids]
    (set-skill-exp! uuid skill-id 1.0))
  {:changed? true
   :skill-ids (vec skill-ids)})

(defn change-category!
  "Apply a category change, clear preset slots, and fire the category-change event.

  The optional `ability-update-fn` can extend the mutation with related ability
  changes such as a forced level adjustment."
  ([uuid new-category]
   (change-category! uuid new-category #(adata/set-category % new-category)))
  ([uuid new-category ability-update-fn]
   (let [state (ensure-state uuid)
         old-category (get-in state [:ability-data :category-id])]
     (when (not= old-category new-category)
       (let [updated-ability (ability-update-fn (:ability-data state))]
         (ps/update-player-state!
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
