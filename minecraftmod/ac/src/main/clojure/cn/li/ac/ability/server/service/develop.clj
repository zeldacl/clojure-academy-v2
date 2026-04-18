(ns cn.li.ac.ability.server.service.develop
  "Service layer for the development (timed learning) process.

  Orchestrates:
  - Starting a development session (learn-skill or level-up)
  - Ticking the development each server tick
  - Completing development (applying results to ability data)
  - Aborting development"
  (:require [cn.li.ac.ability.model.develop :as dev]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.resource :as rdata]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.server.service.learning :as learning]
            [cn.li.ac.ability.server.service.resource :as svc-res]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Start development
;; ============================================================================

(defn start-skill-learning
  "Start learning a skill. Returns {:develop-data :error nil|keyword}.
  Caller must validate conditions before calling."
  [develop-data developer-type skill-id]
  (if (dev/developing? develop-data)
    {:develop-data develop-data :error :already-developing}
    (let [s (skill/get-skill skill-id)]
      (if-not s
        {:develop-data develop-data :error :unknown-skill}
        (let [max-stim (dev/skill-learning-stims (:level s))]
          {:develop-data (dev/start-develop develop-data
                                            developer-type
                                            :learn-skill
                                            {:skill-id skill-id}
                                            max-stim)
           :error nil})))))

(defn start-level-up
  "Start level-up process. Returns {:develop-data :error nil|keyword}."
  [develop-data developer-type current-level]
  (if (dev/developing? develop-data)
    {:develop-data develop-data :error :already-developing}
    (if (>= current-level 5)
      {:develop-data develop-data :error :max-level}
      (let [max-stim (dev/level-up-stims current-level)]
        {:develop-data (dev/start-develop develop-data
                                          developer-type
                                          :level-up
                                          {:target-level (inc current-level)}
                                          max-stim)
         :error nil}))))

;; ============================================================================
;; Tick
;; ============================================================================

(defn tick-develop
  "Advance one tick. Returns {:develop-data :completed? :action-type :action-data}.
  When :completed? is true, caller should apply the result."
  [develop-data]
  (if-not (dev/developing? develop-data)
    {:develop-data develop-data :completed? false}
    (let [ticked (dev/tick-develop develop-data)]
      (if (dev/done? ticked)
        {:develop-data  ticked
         :completed?    true
         :action-type   (:action-type ticked)
         :action-data   (:action-data ticked)}
        {:develop-data ticked :completed? false}))))

;; ============================================================================
;; Complete
;; ============================================================================

(defn apply-completion
  "Apply a completed development result to ability data.
  Returns {:ability-data :resource-data :events [...] :develop-data}."
  [develop-data ability-data resource-data uuid]
  (let [action-type (:action-type develop-data)
        action-data (:action-data develop-data)]
    (case action-type
      :learn-skill
      (let [skill-id (:skill-id action-data)
            {:keys [data event]} (learning/learn-skill ability-data uuid skill-id)
            level    (:level data)
            new-res  (rdata/recalc-max-values resource-data level)]
        {:ability-data  data
         :resource-data new-res
         :events        (if event [event] [])
         :develop-data  (dev/complete-and-reset develop-data)})

      :level-up
      (let [target-level (:target-level action-data)
            old-level    (:level ability-data)
            data2        (adata/set-level ability-data target-level)
            new-res      (-> resource-data
                             (rdata/reset-add-max)
                             (rdata/recalc-max-values target-level))
            event        (evt/make-level-change-event uuid old-level target-level)]
        {:ability-data  data2
         :resource-data new-res
         :events        [event]
         :develop-data  (dev/complete-and-reset develop-data)})

      ;; fallback
      {:ability-data  ability-data
       :resource-data resource-data
       :events        []
       :develop-data  (dev/complete-and-reset develop-data)})))

;; ============================================================================
;; Abort
;; ============================================================================

(defn abort-develop
  "Abort current development. Returns {:develop-data}."
  [develop-data]
  {:develop-data (dev/abort develop-data)})
