(ns cn.li.ac.ability.client.screens.preset-editor
  "Preset editor screen logic (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

;; Editor state
(def ^:private default-editor-state
  {:selected-preset 0
   :selected-skill nil
   :pending-changes {}
   :player-uuid nil})

(def ^:private default-editor-runtime-state
  {:current-owner nil
   :states {}})

(defn create-preset-editor-runtime
  []
  {::runtime ::preset-editor-runtime
   :runtime-state* (atom default-editor-runtime-state)})

(def ^:dynamic *preset-editor-runtime* nil)

(defonce ^:private installed-preset-editor-runtime
  (create-preset-editor-runtime))

(defn- preset-editor-runtime?
  [runtime]
  (and (map? runtime)
       (= ::preset-editor-runtime (::runtime runtime))
       (some? (:runtime-state* runtime))))

(defn call-with-preset-editor-runtime
  [runtime f]
  (when-not (preset-editor-runtime? runtime)
    (throw (ex-info "Expected preset editor runtime"
                    {:runtime runtime})))
  (binding [*preset-editor-runtime* runtime]
    (f)))

(defmacro with-preset-editor-runtime
  [runtime & body]
  `(call-with-preset-editor-runtime ~runtime (fn [] ~@body)))

(defn- current-preset-editor-runtime
  []
  (or *preset-editor-runtime*
      installed-preset-editor-runtime))

(defn- editor-runtime-state-atom
  []
  (:runtime-state* (current-preset-editor-runtime)))

(defn- editor-runtime-state-snapshot
  []
  @(editor-runtime-state-atom))

(defn- require-editor-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Preset editor owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn editor-owner-key
  [owner]
  (let [owner-map (cond
                    (vector? owner) owner
                    (map? owner) owner
                    (some? owner) {:player-uuid owner}
                    :else {})]
    (if (vector? owner-map)
      owner-map
      [(require-editor-owner-value owner ":client-session-id"
                                   (or (:client-session-id owner-map)
                                       (:session-id owner-map)
                                       runtime-hooks/*client-session-id*))
       :preset-editor
       (require-editor-owner-value owner ":player-uuid"
                                   (some-> (or (:player-uuid owner-map)
                                               (:uuid owner-map))
                           str))])))

(defn- with-editor-player-state-owner
  [owner f]
  (let [[session-id _screen-id player-uuid] (editor-owner-key owner)]
    (binding [runtime-hooks/*client-session-id* session-id
              runtime-hooks/*player-state-owner* {:client-session-id session-id
                                                  :player-uuid player-uuid}]
      (f))))

(defn- get-editor-player-state
  [owner]
  (let [[_session-id _screen-id player-uuid] (editor-owner-key owner)]
    (with-editor-player-state-owner owner
      #(ps/get-player-state player-uuid))))

(defn- normalized-store
  [store]
  (if (and (map? store) (contains? store :states))
    store
    (let [owner-key (when (:player-uuid store) (editor-owner-key store))]
      {:current-owner owner-key
       :states (if owner-key {owner-key (merge default-editor-state store)} {})})))

(defn- current-owner-key
  []
  (:current-owner (normalized-store (editor-runtime-state-snapshot))))

(defn editor-state-snapshot
  ([]
   (if-let [owner-key (current-owner-key)]
     (editor-state-snapshot owner-key)
     default-editor-state))
  ([owner]
  (get-in (normalized-store (editor-runtime-state-snapshot)) [:states (editor-owner-key owner)] default-editor-state)))

(defn- swap-editor-state!
  [owner f & args]
  (let [owner-key (editor-owner-key owner)]
    (swap! (editor-runtime-state-atom)
           (fn [store]
             (let [store (normalized-store store)]
               (assoc-in store [:states owner-key]
                         (apply f (get-in store [:states owner-key] default-editor-state) args)))))))

(defn- set-current-owner!
  [owner]
  (let [owner-key (editor-owner-key owner)]
    (swap! (editor-runtime-state-atom) #(assoc (normalized-store %) :current-owner owner-key))
    owner-key))

(defn reset-editor-states-for-test!
  []
  (reset! (editor-runtime-state-atom) default-editor-runtime-state)
  nil)

;; ============================================================================
;; Render Data Builders
;; ============================================================================

(defn- spec-skill-id
  [skill-spec]
  (or (:skill-id skill-spec) (:id skill-spec)))

(defn build-preset-editor-render-data
  "Build complete preset editor render data."
  []
  (let [state (editor-state-snapshot)
        owner-key (current-owner-key)]
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
  [preset-idx]
  (when-let [owner-key (current-owner-key)]
    (swap-editor-state! owner-key assoc :selected-preset preset-idx)))

(defn on-skill-select
  "Handle skill selection from available skills list."
  [skill-id]
  (when-let [owner-key (current-owner-key)]
    (swap-editor-state! owner-key assoc :selected-skill skill-id)))

(defn on-slot-click
  "Handle slot click. Assigns selected skill to slot."
  [slot-idx]
  (when-let [owner-key (current-owner-key)]
    (let [state (editor-state-snapshot owner-key)]
      (when-let [skill-id (:selected-skill state)]
        (let [preset-idx (:selected-preset state)]
          (swap-editor-state! owner-key assoc-in [:pending-changes preset-idx slot-idx] skill-id))))))

(defn on-save-click
  "Handle save button click. Sends all pending changes to server."
  []
  (let [owner-key (current-owner-key)
        state (editor-state-snapshot owner-key)
        render-data (build-preset-editor-render-data)
        available-skills (:available-skills render-data)]
    (doseq [[preset-idx slots] (:pending-changes state)]
      (doseq [[slot-idx skill-id] slots]
        (when-let [skill-info (first (filter #(= (:skill-id %) skill-id) available-skills))]
          (api/req-set-preset-slot! preset-idx slot-idx
                                   (:cat-id skill-info)
                                   (:ctrl-id skill-info)
                                   nil)))))
  (when-let [owner-key (current-owner-key)]
    (swap-editor-state! owner-key assoc :pending-changes {})))

(defn on-set-active-click
  "Handle set active button click."
  []
  (api/req-switch-preset! (:selected-preset (editor-state-snapshot)) nil))

(defn handle-screen-click!
  "Handle clicks inside the preset editor screen using current render data."
  [mouse-x mouse-y]
  (if-let [render-data (build-preset-editor-render-data)]
    (let [clicked? (atom false)]
      (doseq [preset-idx (:presets render-data)]
        (when (and (not @clicked?)
                   (>= mouse-x (+ 10 (* preset-idx 45)))
                   (<= mouse-x (+ 50 (* preset-idx 45)))
                   (>= mouse-y 10) (<= mouse-y 30))
          (on-preset-tab-click preset-idx)
          (reset! clicked? true)))

      (doseq [idx (range 4)]
        (when (and (not @clicked?)
                   (>= mouse-x 10) (<= mouse-x 110)
                   (>= mouse-y (+ 40 (* idx 25)))
                   (<= mouse-y (+ 60 (* idx 25))))
          (on-slot-click idx)
          (reset! clicked? true)))

      (doseq [[idx skill] (map-indexed vector (:available-skills render-data))]
        (when (and (not @clicked?)
                   (>= mouse-x 170) (<= mouse-x 320)
                   (>= mouse-y (+ 60 (* idx 22)))
                   (<= mouse-y (+ 82 (* idx 22))))
          (on-skill-select (:skill-id skill))
          (reset! clicked? true)))

      (when (and (not @clicked?)
                 (>= mouse-x 10) (<= mouse-x 90)
                 (>= mouse-y 200) (<= mouse-y 220))
        (on-save-click)
        (reset! clicked? true))

      (when (and (not @clicked?)
                 (>= mouse-x 100) (<= mouse-x 180)
                 (>= mouse-y 200) (<= mouse-y 220))
        (on-set-active-click)
        (reset! clicked? true))

      (boolean @clicked?))
    false))

(defn open-screen!
  "Open preset editor screen."
  [player-uuid]
  (let [owner {:player-uuid player-uuid}]
    (set-current-owner! owner)
    (swap-editor-state! owner merge default-editor-state {:player-uuid player-uuid}))
  {:command :open-screen
   :screen-type :preset-editor})

(defn close-screen!
  "Close preset editor screen."
  ([]
   (when-let [owner-key (current-owner-key)]
     (close-screen! owner-key)))
  ([owner]
   (swap! (editor-runtime-state-atom)
          (fn [store]
            (let [store (normalized-store store)
                  owner-key (editor-owner-key owner)]
              (cond-> (update store :states dissoc owner-key)
                (= owner-key (:current-owner store)) (assoc :current-owner nil)))))))
