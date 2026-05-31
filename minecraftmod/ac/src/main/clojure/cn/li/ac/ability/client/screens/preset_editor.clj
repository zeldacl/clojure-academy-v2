(ns cn.li.ac.ability.client.screens.preset-editor
  "Preset editor screen logic (AC layer - no Minecraft imports)."
  (:require 
[cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.model.ability :as adata]))

;; Editor state
(def ^:private default-editor-state
  {:selected-preset 0
   :selected-skill nil
   :pending-changes {}
   :player-uuid nil})

(def screen-id :preset-editor)

(defn editor-owner-key
  [owner]
  (read-model/owner-key owner :preset-editor))

(defn- with-editor-player-state-owner
  [owner f]
  (read-model/with-player-state-owner (editor-owner-key owner) f))

(defn- get-editor-player-state
  [owner]
  (read-model/get-player-state (editor-owner-key owner)))

(defn editor-state-snapshot
  ([owner]
   (managed-screens/screen-state screen-id (editor-owner-key owner) default-editor-state)))

(defn- swap-editor-state!
  [owner f & args]
  (let [owner-key (editor-owner-key owner)]
    (apply managed-screens/update-screen-state! screen-id owner-key default-editor-state f args)))

(defn reset-editor-states-for-test!
  []
  (managed-screens/reset-managed-screen-state-for-test!)
  nil)

;; ============================================================================
;; Render Data Builders
;; ============================================================================

(defn- spec-skill-id
  [skill-spec]
  (or (:skill-id skill-spec) (:id skill-spec)))

(defn build-preset-editor-render-data
  "Build complete preset editor render data."
  [owner]
  (let [state (editor-state-snapshot owner)
        owner-key (editor-owner-key owner)]
  (when-let [_player-uuid (:player-uuid state)]
    (when-let [player-state (and owner-key (get-editor-player-state owner-key))]
      (let [ability-data (:ability-data player-state)
            preset-data (:preset-data player-state)
            category-id (:category-id ability-data)
            learned-skills (when category-id
                     (filter #(adata/is-learned? ability-data (spec-skill-id %))
                       (skill-query/get-skills-for-category category-id)))
                controllable-skills (filter #(and (:enabled %) (:controllable? %)) learned-skills)
            current-preset (:selected-preset state)
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
                              skill-obj (skill-query/get-skill-by-controllable cat-id ctrl-id)]
                          (when skill-obj
                            {:idx idx
                             :skill-id skill-obj
                             :skill-name (:name (skill-registry/get-skill skill-obj))
                             :skill-icon (skill-query/get-skill-icon-path skill-obj)}))))))
         :available-skills (mapv
                             (fn [s]
                               {:skill-id (spec-skill-id s)
                                :skill-name (:name s)
                                :skill-icon (skill-query/get-skill-icon-path (spec-skill-id s))
                                :cat-id (:category-id s)
                                :ctrl-id (or (:ctrl-id s) (spec-skill-id s))})
                             controllable-skills)
         :selected-skill (:selected-skill state)
         :has-changes (not (empty? (:pending-changes state)))})))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn on-preset-tab-click
  "Handle preset tab click."
  [owner preset-idx]
  (swap-editor-state! owner assoc :selected-preset preset-idx))

(defn on-skill-select
  "Handle skill selection from available skills list."
  [owner skill-id]
  (swap-editor-state! owner assoc :selected-skill skill-id))

(defn on-slot-click
  "Handle slot click. Assigns selected skill to slot."
  [owner slot-idx]
  (let [state (editor-state-snapshot owner)]
    (when-let [skill-id (:selected-skill state)]
      (let [preset-idx (:selected-preset state)]
        (swap-editor-state! owner assoc-in [:pending-changes preset-idx slot-idx] skill-id)))))

(defn on-save-click
  "Handle save button click. Sends all pending changes to server."
  [owner]
  (let [state (editor-state-snapshot owner)
        render-data (build-preset-editor-render-data owner)
        available-skills (:available-skills render-data)]
    (doseq [[preset-idx slots] (:pending-changes state)]
      (doseq [[slot-idx skill-id] slots]
        (when-let [skill-info (first (filter #(= (:skill-id %) skill-id) available-skills))]
          (api/req-set-preset-slot! preset-idx slot-idx
                                   (:cat-id skill-info)
                                   (:ctrl-id skill-info)
                                   nil)))))
  (swap-editor-state! owner assoc :pending-changes {}))

(defn on-set-active-click
  "Handle set active button click."
  [owner]
  (api/req-switch-preset! (:selected-preset (editor-state-snapshot owner)) nil))

(defn handle-screen-click!
  "Handle clicks inside the preset editor screen using current render data."
  [owner mouse-x mouse-y]
  (if-let [render-data (build-preset-editor-render-data owner)]
    (let [clicked? (atom false)]
      (doseq [preset-idx (:presets render-data)]
        (when (and (not @clicked?)
                   (>= mouse-x (+ 10 (* preset-idx 45)))
                   (<= mouse-x (+ 50 (* preset-idx 45)))
                   (>= mouse-y 10) (<= mouse-y 30))
          (on-preset-tab-click owner preset-idx)
          (reset! clicked? true)))

      (doseq [idx (range 4)]
        (when (and (not @clicked?)
                   (>= mouse-x 10) (<= mouse-x 110)
                   (>= mouse-y (+ 40 (* idx 25)))
                   (<= mouse-y (+ 60 (* idx 25))))
                  (on-slot-click owner idx)
          (reset! clicked? true)))

      (doseq [[idx skill] (map-indexed vector (:available-skills render-data))]
        (when (and (not @clicked?)
                   (>= mouse-x 170) (<= mouse-x 320)
                   (>= mouse-y (+ 60 (* idx 22)))
                   (<= mouse-y (+ 82 (* idx 22))))
                  (on-skill-select owner (:skill-id skill))
          (reset! clicked? true)))

      (when (and (not @clicked?)
                 (>= mouse-x 10) (<= mouse-x 90)
                 (>= mouse-y 200) (<= mouse-y 220))
              (on-save-click owner)
        (reset! clicked? true))

      (when (and (not @clicked?)
                 (>= mouse-x 100) (<= mouse-x 180)
                 (>= mouse-y 200) (<= mouse-y 220))
              (on-set-active-click owner)
        (reset! clicked? true))

      (boolean @clicked?))
    false))

(defn open-screen!
  "Open preset editor screen."
  [owner]
  (let [owner-key (editor-owner-key owner)
        player-uuid (nth owner-key 2)]
    (managed-screens/set-active-owner! screen-id owner-key)
    (swap-editor-state! owner merge default-editor-state {:player-uuid player-uuid}))
  {:command :open-screen
   :screen-type :preset-editor})

(defn close-screen!
  "Close preset editor screen."
  ([owner]
   (managed-screens/clear-screen-state! screen-id (editor-owner-key owner))))

