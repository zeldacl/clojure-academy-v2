(ns cn.li.ac.block.developer.session
  "Server-side timed development session for the developer block."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.model.develop :as dev-model]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.rules.develop-rules :as develop-rules]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.entity :as entity]))


(defn clear-session
  [state]
  (-> state
      (assoc :is-developing false
             :development-progress 0.0
             :development-data nil
             :development-action nil
             :development-payload nil)))

(defn- developer-type-from-state [state]
  (keyword (or (:tier state) :normal)))

(defn- find-induction-factor [player]
  (some (fn [[item-id category]]
          (when (pos? (int (entity/player-count-item-by-id player item-id)))
            {:item-id item-id :category category}))
        (special-items/induction-factor-catalog)))

(defn- ability-state [player]
  (let [session-id (runtime-hooks/require-player-state-session-id "developer.session")
        pid (uuid/player-uuid player)]
    (store/get-or-create-player-state! session-id pid)))

(defn- validate-common [state player]
  (cond
    (not (:structure-valid state false)) {:ok? false :reason "invalid-structure"}
    (:is-developing state) {:ok? false :reason "already-developing"}
    :else
    (let [pid (uuid/player-uuid player)
          holder (str (:user-uuid state ""))]
      (if (and (not (str/blank? holder)) (not= holder pid))
        {:ok? false :reason "wrong-user"}
        {:ok? true :player-uuid pid}))))

(defn- begin-session [state developer-type action payload develop-data]
  (-> state
      (assoc :is-developing true
             :development-data develop-data
             :development-action action
             :development-payload payload
             :development-progress 0.0
             :tier (name developer-type))))

(defn- ok-session [state dev-type action payload develop-data]
  {:ok? true :state (begin-session state dev-type action payload develop-data)})

(defn- err [reason] {:ok? false :reason reason})

(defn- start-category-level-up [state dev-type ability-data cat-id level]
  (let [skills (skill-query/get-controllable-skills-at-level cat-id level)
        cat-rate (category/get-prog-incr-rate cat-id)
        can? (learning-rules/can-level-up? ability-data skills cat-rate
                                           (cfg/prog-incr-rate) (cfg/max-level))]
    (if-not can?
      (err "not-enough-progress")
      (let [{:keys [develop-data error]}
            (develop-rules/start-level-up (dev-model/new-develop-data) dev-type level)]
        (if error (err (name error)) (ok-session state dev-type :level-up {} develop-data))))))

(defn- start-awaken-action [state player dev-type]
  (if-let [{:keys [item-id category]} (find-induction-factor player)]
    (let [{:keys [develop-data error]}
          (develop-rules/start-level-up (dev-model/new-develop-data) dev-type 0)]
      (cond
        error (err (name error))
        (not (entity/player-consume-item-by-id! player item-id 1)) (err "missing-induction-factor")
        :else (ok-session state dev-type :awaken
                          {:target-category category :induction-item-id item-id}
                          develop-data)))
    (err "missing-induction-factor")))

(defn- start-level-up-action [state player dev-type ability-data]
  (let [cat-id (:category-id ability-data)
        level (int (:level ability-data 1))]
    (if cat-id
      (start-category-level-up state dev-type ability-data cat-id level)
      (start-awaken-action state player dev-type))))

(defn- start-learn-skill-action [state dev-type ability-data skill-id]
  (let [cat-id (:category-id ability-data)
        level (int (:level ability-data 1))]
    (if-not cat-id
      (err "no-category")
      (if-let [skill-spec (skill-registry/get-skill (keyword skill-id))]
        (let [conditions (learning-rules/check-all-conditions skill-spec ability-data level dev-type)]
          (if-not (:pass? conditions)
            (err "conditions-not-met")
            (let [{:keys [develop-data error]}
                  (develop-rules/start-skill-learning
                    (dev-model/new-develop-data) dev-type (:id skill-spec))]
              (if error
                (err (name error))
                (ok-session state dev-type :learn-skill {:skill-id (:id skill-spec)} develop-data)))))
        (err "unknown-skill")))))

(defn- start-reset-action [state player dev-type ability-data]
  (let [cat-id (:category-id ability-data)
        level (int (:level ability-data 1))]
    (cond
      (not (developer/gte? dev-type :advanced)) (err "requires-advanced-developer")
      (< level 3) (err "level-too-low")
      (not= special-items/magnetic-coil-item-id (entity/player-get-main-hand-item-id player)) (err "missing-magnetic-coil")
      :else
      (if-let [{:keys [item-id category]} (find-induction-factor player)]
        (cond
          (= category cat-id) (err "same-category")
          :else
          (let [{:keys [develop-data error]}
                (develop-rules/start-level-up (dev-model/new-develop-data) dev-type level)]
            (cond
              error (err (name error))
              (not (and (entity/player-consume-item-by-id! player item-id 1)
                        (entity/player-consume-main-hand-item! player 1))) (err "missing-materials")
              :else (ok-session state dev-type :reset
                                {:target-category category
                                 :new-level (max 1 (dec level))
                                 :induction-item-id item-id}
                                develop-data))))
        (err "missing-induction-factor")))))

(defn validate-and-start
  [state player {:keys [action skill-id]}]
  (if-let [common-error (some-> (validate-common state player) (#(when-not (:ok? %) %)))]
    common-error
    (let [dev-type (developer-type-from-state state)
          ability-data (:ability-data (ability-state player))]
      (case (keyword (or action :level-up))
        :level-up (start-level-up-action state player dev-type ability-data)
        :learn-skill (start-learn-skill-action state dev-type ability-data skill-id)
        :reset (start-reset-action state player dev-type ability-data)
        (err "unknown-action")))))

(defn tick-development-state
  [state]
  (if-not (:is-developing state)
    state
    (let [dd (or (:development-data state) (dev-model/new-develop-data))]
      (if-not (dev-model/developing? dd)
        (clear-session state)
        (let [dev-type (developer-type-from-state state)
              ept (dev-model/energy-per-tick dev-type)
              energy (double (:energy state 0.0))]
          (if (< energy ept)
            (-> state
                (assoc :development-data (dev-model/fail dd)
                       :is-developing false
                       :development-progress (dev-model/progress dd)))
            (let [{:keys [develop-data]} (develop-rules/tick-develop dd)
                  ticked develop-data
                  prog (dev-model/progress ticked)]
              (if (dev-model/done? ticked)
                (assoc state
                       :development-data ticked
                       :development-progress 1.0
                       :is-developing false
                       :development-complete? true)
                (-> state
                    (assoc :development-data ticked :development-progress prog)
                    (update :energy - ept))))))))))

(defn- run-completion-command! [player-uuid action payload session-id]
  (let [session-id (or session-id
                       (runtime-hooks/require-player-state-session-id "developer.session"))]
    (case action
      :awaken
      (command-rt/run-command-in-session!
        session-id player-uuid
        {:command :change-category :new-category (:target-category payload)})
      :level-up
      (command-rt/run-command-in-session!
        session-id player-uuid {:command :level-up :force? true})
      :learn-skill
      (command-rt/run-command-in-session!
        session-id player-uuid
        {:command :learn-skill
         :skill-id (:skill-id payload)
         :check-conditions? false})
      :reset
      (command-rt/run-command-in-session!
        session-id player-uuid
        {:command :change-category-with-level
         :new-category (:target-category payload)
         :new-level (:new-level payload)})
      nil)))

(defn apply-completion!
  [state]
  (when (:development-complete? state)
    (let [pid (str (:user-uuid state ""))]
      (when-not (str/blank? pid)
        (run-completion-command! pid
                                 (:development-action state)
                                 (:development-payload state)
                                 (:player-state-session-id state)))))
  nil)
