(ns cn.li.ac.ability.client.screens.preset-editor
  "Preset editor screen logic (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.mcmod.i18n :as i18n]))


;; Editor state
(def ^:private default-editor-state
  {:selected-preset 0
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

(defn- as-keyword
  "NBT/network sync may rehydrate keywords as strings; normalize for set lookups."
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn- learned-skill-ids
  [ability-data]
  (into #{} (map as-keyword) (:learned-skills ability-data #{})))

(defn- skill-learned?
  [learned-ids skill-spec]
  (contains? learned-ids (as-keyword (spec-skill-id skill-spec))))

(defn- skill-bindable?
  "Enabled + controllable; treat missing flags as true (registry defaults)."
  [skill-spec]
  (and (not (false? (:enabled skill-spec)))
       (not (false? (:controllable? skill-spec)))))

(defn- slot-info
  "Build slot info map for a controllable pair. Returns nil if skill not found."
  [pair]
  (when (and (vector? pair) (= 2 (count pair)))
    (let [[cat-id ctrl-id] pair
          skill-obj (skill-query/get-skill-by-controllable cat-id ctrl-id)]
      (when skill-obj
        (let [spec (skill-registry/get-skill skill-obj)
              name-key (:name-key spec)
              skill-name (if name-key
                           (or (i18n/translate name-key) (name skill-obj))
                           (or (:name spec) (name skill-obj)))]
          {:skill-id skill-obj
           :skill-name skill-name
           :skill-icon (skill-query/get-skill-icon-path skill-obj)
           :cat-id cat-id
           :ctrl-id ctrl-id})))))

(defn- presets-all-slots
  "Build slot info for all 4 presets. Returns map {preset-idx [slot-0 slot-1 slot-2 slot-3]}."
  [slots-data]
  (into {}
    (for [preset-idx (range 4)]
      [preset-idx
       (mapv (fn [key-idx]
               (slot-info (get slots-data [preset-idx key-idx])))
             (range 4))])))

(defn- assigned-ctrl-ids
  "Set of controllable ids already assigned in the given preset."
  [slots-data preset-idx]
  (set (keep (fn [key-idx]
               (when-let [pair (get slots-data [preset-idx key-idx])]
                 (second pair)))
             (range 4))))

(defn build-preset-editor-render-data
  "Build complete preset editor render data.
   Returns nil if player state is unavailable."
  [owner]
  (let [state (editor-state-snapshot owner)
        owner-key (editor-owner-key owner)]
    (when-let [_player-uuid (:player-uuid state)]
      (when-let [player-state (and owner-key (get-editor-player-state owner))]
        (let [ability-data (:ability-data player-state)
              preset-data (:preset-data player-state)
              category-id (as-keyword (:category-id ability-data))
              slots-data (:slots preset-data {})
              current-preset (:selected-preset state)
              active-preset (:active-preset preset-data 0)
              learned-ids (learned-skill-ids ability-data)
              ;; All category skills, then learned ∩ bindable (nil flags OK).
              category-skills (if category-id
                                (skill-query/get-skills-for-category category-id)
                                [])
              learned-bindable (->> category-skills
                                    (filter #(skill-learned? learned-ids %))
                                    (filter skill-bindable?))
              assigned-ids (assigned-ctrl-ids slots-data current-preset)
              available-for-preset (remove (fn [s]
                                             (contains? assigned-ids
                                                        (or (:ctrl-id s) (spec-skill-id s))))
                                           learned-bindable)]
          {:presets (range 4)
           :selected-preset current-preset
           :active-preset active-preset
           :all-preset-slots (presets-all-slots slots-data)
           :slots (mapv (fn [idx] (slot-info (get slots-data [current-preset idx]))) (range 4))
           :available-skills (mapv
                               (fn [s]
                                 (let [sid (spec-skill-id s)]
                                   {:skill-id sid
                                    :skill-name (let [nk (:name-key s)]
                                                  (if nk (or (i18n/translate nk) (name sid))
                                                      (or (:name s) (name sid))))
                                    :skill-icon (skill-query/get-skill-icon-path sid)
                                    :cat-id (as-keyword (:category-id s))
                                    :ctrl-id (or (:ctrl-id s) sid)}))
                               available-for-preset)})))))

(defn selector-debug-snapshot
  "Return a compact diagnostic map for selector filtering.
   Used only for runtime troubleshooting of missing skills in preset editor."
  [owner]
  (let [state (editor-state-snapshot owner)
        owner-key (editor-owner-key owner)]
    (when-let [_player-uuid (:player-uuid state)]
      (when-let [player-state (and owner-key (get-editor-player-state owner))]
        (let [ability-data (:ability-data player-state)
              preset-data (:preset-data player-state)
              category-id (as-keyword (:category-id ability-data))
              learned (learned-skill-ids ability-data)
              slots-data (:slots preset-data {})
              current-preset (:selected-preset state)
              all-skills (if category-id
                           (vec (skill-query/get-skills-for-category category-id))
                           [])
              controllable (if category-id
                             (vec (skill-query/get-controllable-skills-for-category category-id))
                             [])
              learned-skills (vec (filter #(skill-learned? learned %) all-skills))
              controllable-skills (vec (filter skill-bindable? (filter #(skill-learned? learned %) controllable)))
              assigned-ids (assigned-ctrl-ids slots-data current-preset)
              available-for-preset (vec (remove (fn [s]
                                                  (contains? assigned-ids
                                                             (or (:ctrl-id s) (spec-skill-id s))))
                                                controllable-skills))]
          {:category-id category-id
           :selected-preset current-preset
           :active-preset (:active-preset preset-data 0)
           :learned-ids (vec (sort (map name learned)))
           :slots (into {}
                        (for [idx (range 4)]
                          [idx (get slots-data [current-preset idx])]))
           :assigned-ctrl-ids (vec (sort (map name assigned-ids)))
           :all-skills (mapv (fn [s]
                               {:id (spec-skill-id s)
                                :ctrl-id (:ctrl-id s)
                                :enabled (:enabled s)
                                :controllable? (:controllable? s)})
                             all-skills)
           :learned-visible-ids (vec (sort (map (comp name spec-skill-id) learned-skills)))
           :controllable-visible-ids (vec (sort (map (comp name spec-skill-id) controllable-skills)))
           :available-skill-ids (vec (sort (map (comp name spec-skill-id) available-for-preset)))})))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn on-preset-tab-click
  "Handle preset tab click."
  [owner preset-idx]
  (swap-editor-state! owner assoc :selected-preset preset-idx))

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
