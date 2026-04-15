(ns cn.li.ac.ability.client.screens.preset-editor
  "Preset editor screen logic (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client-api :as api]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.model.ability-data :as adata]
            [cn.li.ac.ability.player-state :as ps]))

;; Editor state
(defonce ^:private editor-state
  (atom {:selected-preset 0
         :selected-skill nil
         :pending-changes {}
         :player-uuid nil}))

;; ============================================================================
;; Render Data Builders
;; ============================================================================

(defn build-preset-editor-render-data
  "Build complete preset editor render data."
  []
  (when-let [player-uuid (:player-uuid @editor-state)]
    (when-let [player-state (ps/get-player-state player-uuid)]
      (let [ability-data (:ability-data player-state)
            preset-data (:preset-data player-state)
            category-id (:category-id ability-data)
            learned-skills (when category-id
                            (filter #(adata/is-learned? ability-data (:skill-id %))
                                   (skill/get-skills-for-category category-id)))
            controllable-skills (filter #(skill/can-control? %) learned-skills)
            current-preset (:selected-preset @editor-state)
            active-preset (:active-preset preset-data)
            slots (:slots preset-data)]
        {:presets (range 4)
         :selected-preset current-preset
         :active-preset active-preset
         :slots (vec
                  (for [idx (range 4)]
                    (when-let [slot (get-in slots [[current-preset idx]])]
                      (when (and (vector? slot) (= 2 (count slot)))
                        (let [[cat-id ctrl-id] slot
                              skill-obj (skill/get-skill-by-controllable cat-id ctrl-id)]
                          (when skill-obj
                            {:idx idx
                             :skill-id skill-obj
                             :skill-name (:name (skill/get-skill skill-obj))
                             :skill-icon (skill/get-skill-icon-path skill-obj)}))))))
         :available-skills (mapv
                             (fn [s]
                               {:skill-id (:skill-id s)
                                :skill-name (:name s)
                                :skill-icon (skill/get-skill-icon-path (:skill-id s))
                                :cat-id (:category-id s)
                                :ctrl-id (or (:ctrl-id s) (:skill-id s))})
                             controllable-skills)
         :selected-skill (:selected-skill @editor-state)
         :has-changes (not (empty? (:pending-changes @editor-state)))}))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn on-preset-tab-click
  "Handle preset tab click."
  [preset-idx]
  (swap! editor-state assoc :selected-preset preset-idx))

(defn on-skill-select
  "Handle skill selection from available skills list."
  [skill-id]
  (swap! editor-state assoc :selected-skill skill-id))

(defn on-slot-click
  "Handle slot click. Assigns selected skill to slot."
  [slot-idx]
  (when-let [skill-id (:selected-skill @editor-state)]
    (let [preset-idx (:selected-preset @editor-state)]
      (swap! editor-state assoc-in [:pending-changes preset-idx slot-idx] skill-id))))

(defn on-save-click
  "Handle save button click. Sends all pending changes to server."
  []
  (let [render-data (build-preset-editor-render-data)
        available-skills (:available-skills render-data)]
    (doseq [[preset-idx slots] (:pending-changes @editor-state)]
      (doseq [[slot-idx skill-id] slots]
        (when-let [skill-info (first (filter #(= (:skill-id %) skill-id) available-skills))]
          (api/req-set-preset-slot! preset-idx slot-idx
                                   (:cat-id skill-info)
                                   (:ctrl-id skill-info)
                                   nil)))))
  (swap! editor-state assoc :pending-changes {}))

(defn on-set-active-click
  "Handle set active button click."
  []
  (api/req-switch-preset! (:selected-preset @editor-state) nil))

(defn open-screen!
  "Open preset editor screen."
  [player-uuid]
  (swap! editor-state assoc :player-uuid player-uuid)
  {:command :open-screen
   :screen-type :preset-editor})

(defn close-screen!
  "Close preset editor screen."
  []
  (swap! editor-state assoc :player-uuid nil :selected-skill nil :pending-changes {}))
