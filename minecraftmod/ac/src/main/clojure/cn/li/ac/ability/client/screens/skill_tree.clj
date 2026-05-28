(ns cn.li.ac.ability.client.screens.skill-tree
  "Skill tree screen logic (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.registry.skill-query :as skill]
            [cn.li.ac.ability.server.service.learning :as learning]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.i18n :as i18n]))

;; Screen state (no Minecraft imports)
(def ^:private default-screen-state
  {:hover-skill nil
   :player-uuid nil
   :learn-context nil})

(def ^:private default-screen-runtime-state
  {:current-owner nil
   :states {}})

(defn create-skill-tree-screen-runtime
  []
  {::runtime ::skill-tree-screen-runtime
   :runtime-state* (atom default-screen-runtime-state)})

(def ^:dynamic *skill-tree-screen-runtime* nil)

(defonce ^:private installed-skill-tree-screen-runtime
  (create-skill-tree-screen-runtime))

(defn- skill-tree-screen-runtime?
  [runtime]
  (and (map? runtime)
       (= ::skill-tree-screen-runtime (::runtime runtime))
       (some? (:runtime-state* runtime))))

(defn call-with-skill-tree-screen-runtime
  [runtime f]
  (when-not (skill-tree-screen-runtime? runtime)
    (throw (ex-info "Expected skill tree screen runtime"
                    {:runtime runtime})))
  (binding [*skill-tree-screen-runtime* runtime]
    (f)))

(defmacro with-skill-tree-screen-runtime
  [runtime & body]
  `(call-with-skill-tree-screen-runtime ~runtime (fn [] ~@body)))

(defn- current-skill-tree-screen-runtime
  []
  (or *skill-tree-screen-runtime*
      installed-skill-tree-screen-runtime))

(defn- screen-runtime-state-atom
  []
  (:runtime-state* (current-skill-tree-screen-runtime)))

(defn- screen-runtime-state-snapshot
  []
  @(screen-runtime-state-atom))

(defn- require-screen-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Skill tree screen owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn screen-owner-key
  [owner]
  (let [owner-map (cond
                    (vector? owner) owner
                    (map? owner) owner
                    (some? owner) {:player-uuid owner}
                    :else {})]
    (if (vector? owner-map)
      owner-map
      [(require-screen-owner-value owner ":client-session-id"
                                   (or (:client-session-id owner-map)
                                       (:session-id owner-map)
                                       runtime-hooks/*client-session-id*))
       :skill-tree
       (require-screen-owner-value owner ":player-uuid"
                                   (some-> (or (:player-uuid owner-map)
                                               (:uuid owner-map))
                           str))])))

(defn- with-screen-player-state-owner
  [owner f]
  (let [[session-id _screen-id player-uuid] (screen-owner-key owner)]
    (binding [runtime-hooks/*client-session-id* session-id
              runtime-hooks/*player-state-owner* {:client-session-id session-id
                                                  :player-uuid player-uuid}]
      (f))))

(defn- get-screen-player-state
  [owner]
  (let [[_session-id _screen-id player-uuid] (screen-owner-key owner)]
    (with-screen-player-state-owner owner
      #(ps/get-player-state player-uuid))))

(defn- ensure-screen-player-state!
  [owner]
  (let [[_session-id _screen-id player-uuid] (screen-owner-key owner)]
    (with-screen-player-state-owner owner
      #(ps/get-or-create-player-state! player-uuid))))

(defn- normalized-store
  [store]
  (if (and (map? store) (contains? store :states))
    store
    (let [owner-key (when (:player-uuid store) (screen-owner-key store))]
      {:current-owner owner-key
       :states (if owner-key {owner-key (merge default-screen-state store)} {})})))

(defn- current-owner-key
  []
  (:current-owner (normalized-store (screen-runtime-state-snapshot))))

(defn screen-state-snapshot
  ([]
   (if-let [owner-key (current-owner-key)]
     (screen-state-snapshot owner-key)
     default-screen-state))
  ([owner]
  (get-in (normalized-store (screen-runtime-state-snapshot)) [:states (screen-owner-key owner)] default-screen-state)))

(defn- swap-screen-state!
  [owner f & args]
  (let [owner-key (screen-owner-key owner)]
    (swap! (screen-runtime-state-atom)
           (fn [store]
             (let [store (normalized-store store)]
               (assoc-in store [:states owner-key]
                         (apply f (get-in store [:states owner-key] default-screen-state) args)))))))

(defn- set-current-owner!
  [owner]
  (let [owner-key (screen-owner-key owner)]
    (swap! (screen-runtime-state-atom) #(assoc (normalized-store %) :current-owner owner-key))
    owner-key))

(defn reset-screen-states-for-test!
  []
  (reset! (screen-runtime-state-atom) default-screen-runtime-state)
  nil)

(def ^:private max-progress-segments 24)

(defn- translate-field
  [spec text-key fallback]
  (if-let [key-name (get spec text-key)]
    (i18n/translate key-name)
    fallback))

;; ============================================================================
;; Layout Calculations (Pure Functions)
;; ============================================================================

(defn calculate-skill-positions
  "Calculate radial layout positions for skills."
  [skills]
  (let [ordered-skills (vec (sort-by (juxt :level :id) skills))
        center-x 200
        center-y 120
        radius 80
        skill-count (max 1 (count ordered-skills))]
    (map-indexed
      (fn [idx skill]
        (if-let [[px py] (:ui-position skill)]
          {:skill skill :x (int px) :y (int py)}
          (let [angle (* idx (/ (* 2 Math/PI) skill-count))]
            {:skill skill
             :x (int (+ center-x (* radius (Math/cos angle))))
             :y (int (+ center-y (* radius (Math/sin angle))))})))
      ordered-skills)))

(defn- build-skill-connections
  [skill-positions player-state developer-type]
  (let [ability-data (:ability-data player-state)
        node-by-id (into {}
                         (map (fn [{:keys [skill] :as node}]
                                [(:id skill) node]))
                         skill-positions)]
    (vec
      (remove nil?
    (apply concat
      (map (fn [{:keys [skill x y]}]
        (let [target-skill-id (:id skill)
              locked? (not (:pass? (learning/check-all-conditions
                 target-skill-id
                 ability-data
                 (:level ability-data)
                 developer-type)))]
          (for [{source-skill-id :skill-id min-exp :min-exp}
           (:prerequisites skill)
           :let [{from-x :x from-y :y} (get node-by-id source-skill-id)]
           :when from-x]
            {:from-x (+ from-x 10)
             :from-y (+ from-y 10)
             :to-x (+ x 10)
             :to-y (+ y 10)
             :satisfied? (>= (or (adata/get-skill-exp ability-data source-skill-id) 0.0)
              (double min-exp))
             :locked? locked?})))
           skill-positions))))))

;; ============================================================================
;; Render Data Builders
;; ============================================================================

(defn build-skill-node-render-data
  "Build render data for a single skill node."
  [skill-pos player-state developer-type]
  (let [{:keys [skill x y]} skill-pos
        skill-id (or (:skill-id skill) (:id skill))
        ability-data (:ability-data player-state)
        learned? (adata/is-learned? ability-data skill-id)
        ;; check-all-conditions needs: skill-id, ability-data, player-level, developer-type
        conditions (learning/check-all-conditions
                     skill-id
                     ability-data
                     (:level ability-data)
                     developer-type)
        skill-exp (double (or (adata/get-skill-exp ability-data skill-id) 0.0))
          progress (double (max 0.0 (min 1.0 skill-exp)))]
    {:x x
     :y y
     :learned learned?
     :can-learn (:pass? conditions)
     :conditions (:failures conditions)
     :skill-id skill-id
        :skill-name (or (:name skill)
                  (translate-field skill :name-key (name skill-id))
                  (name skill-id))
        :skill-description (translate-field skill :description-key "")
     :skill-icon (skill/get-skill-icon-path skill-id)
     :skill-level (:level skill)
     :exp progress
     :progress-segments (int (Math/round (* progress max-progress-segments)))}))

(defn build-ability-info-render-data
  "Build render data for ability info panel."
  [player-state]
  (let [ability-data (:ability-data player-state)
        resource-data (:resource-data player-state)
        category (:category ability-data)]
    {:category-name (or (:name category)
                        (translate-field category :name-key "Unknown")
                        "Unknown")
     :level (:level ability-data)
     :cp {:cur (:cur-cp resource-data)
          :max (:max-cp resource-data)}
     :overload {:cur (:cur-overload resource-data)
                :max (:max-overload resource-data)}
     :can-level-up (learning/can-level-up? ability-data)}))

(defn build-screen-render-data
  "Build complete screen render data. Called by forge layer."
  []
  (let [state (screen-state-snapshot)
        owner-key (current-owner-key)]
  (when-let [_player-uuid (:player-uuid state)]
    (when-let [player-state (and owner-key (get-screen-player-state owner-key))]
      (let [ability-data (:ability-data player-state)
            category (:category ability-data)
            skills (when category
                    (skill/get-skills-for-category (:category-id category)))
            positions (when skills
                       (calculate-skill-positions skills))
            dev-type (or (:developer-type (:learn-context state)) :normal)]
        {:ability-info (build-ability-info-render-data player-state)
         :category-color (:color category)
         :skill-nodes (when positions
                       (mapv #(build-skill-node-render-data % player-state dev-type) positions))
         :connections (when positions
            (build-skill-connections positions player-state dev-type))
          :hover-skill (:hover-skill state)})))))

;; ============================================================================
;; Event Handlers (Called by Forge Layer)
;; ============================================================================

(defn on-skill-click
  "Handle skill node click. Attempts to learn the skill if conditions are met."
  [skill-id]
  (let [state (screen-state-snapshot)
        owner-key (current-owner-key)]
  (when-let [_player-uuid (:player-uuid state)]
    (when-let [player-state (and owner-key (get-screen-player-state owner-key))]
      (let [ability-data (:ability-data player-state)
            ctx (:learn-context state)
            dev-type (or (:developer-type ctx) :normal)
            conditions (learning/check-all-conditions
                           skill-id
                           ability-data
                           (:level ability-data)
                           dev-type)
            pos-extra (when (every? number? [(:pos-x ctx) (:pos-y ctx) (:pos-z ctx)])
                        (select-keys ctx [:pos-x :pos-y :pos-z]))]
        (when (:pass? conditions)
          (api/req-learn-skill! skill-id pos-extra nil)))))))

(defn on-level-up-click
  "Handle level-up button click."
  []
  (api/req-level-up! nil))

(defn handle-screen-click!
  "Handle clicks inside the skill tree screen using current render data."
  [mouse-x mouse-y]
  (if-let [render-data (build-screen-render-data)]
    (let [clicked? (atom false)]
      (doseq [node (:skill-nodes render-data)]
        (when (and node (not @clicked?))
          (let [dx (- mouse-x (:x node))
                dy (- mouse-y (:y node))
                dist-sq (+ (* dx dx) (* dy dy))]
            (when (< dist-sq 400)
              (on-skill-click (:skill-id node))
              (reset! clicked? true)))))

      (when (and (not @clicked?)
                 (get-in render-data [:ability-info :can-level-up])
                 (>= mouse-x 10) (<= mouse-x 90)
                 (>= mouse-y 200) (<= mouse-y 220))
        (on-level-up-click)
        (reset! clicked? true))

      (boolean @clicked?))
    false))

(defn on-mouse-move
  "Handle mouse movement for hover detection."
  [mouse-x mouse-y]
  (let [render-data (build-screen-render-data)
        skill-nodes (:skill-nodes render-data)
        hovered (when skill-nodes
                 (first
                   (filter
                     (fn [node]
                       (let [dx (- mouse-x (:x node))
                             dy (- mouse-y (:y node))
                             dist-sq (+ (* dx dx) (* dy dy))]
                         (< dist-sq 400))) ; 20px radius squared
                     skill-nodes)))]
    (when-let [owner-key (current-owner-key)]
      (swap-screen-state! owner-key assoc :hover-skill (:skill-id hovered)))))

(defn open-screen!
  "Open skill tree screen. Returns command for forge layer.
  Optional `learn-context`: `{:pos-x :pos-y :pos-z :developer-type}` from Ability Developer."
  ([player-uuid]
   (open-screen! player-uuid nil))
  ([player-uuid learn-context]
    ;; 确保player-state存在，防止UI卡死
    (let [owner {:player-uuid player-uuid}]
      (ensure-screen-player-state! owner)
      (set-current-owner! owner)
      (swap-screen-state! owner merge default-screen-state
                          {:player-uuid player-uuid
                           :learn-context learn-context}))
    {:command :open-screen
     :screen-type :skill-tree}))

(defn close-screen!
  "Close skill tree screen and clean up state."
  ([]
   (when-let [owner-key (current-owner-key)]
     (close-screen! owner-key)))
  ([owner]
   (swap! (screen-runtime-state-atom)
          (fn [store]
            (let [store (normalized-store store)
                  owner-key (screen-owner-key owner)]
              (cond-> (update store :states dissoc owner-key)
                (= owner-key (:current-owner store)) (assoc :current-owner nil)))))))

;; ============================================================================
;; Draw Ops (for generic forge screen host)
;; ============================================================================

(defn- line->fill-ops
  [{:keys [from-x from-y to-x to-y satisfied? locked?]}]
  (let [color (cond
                locked? 0x60444444
                satisfied? 0xB4A7FF7A
                :else 0x80999999)
        x1 (int from-x)
        y1 (int from-y)
        x2 (int to-x)
        y2 (int to-y)
        dx (- x2 x1)
        dy (- y2 y1)
        steps (max 1 (int (Math/ceil (Math/max (Math/abs dx) (Math/abs dy)))))]
    (vec
      (for [i (range (inc steps))]
        (let [t (/ i (double steps))
              x (+ x1 (* dx t))
              y (+ y1 (* dy t))]
          {:kind :fill :x (int x) :y (int y) :w 1 :h 1 :color color})))))

(defn- node-ops
  [{:keys [x y learned can-learn skill-name skill-icon progress-segments exp]}]
  (let [base-color (cond
                     learned 0xCC66CC66
                     can-learn 0xCC6699FF
                     :else 0xAA444444)]
    [{:kind :fill :x x :y y :w 20 :h 20 :color base-color}
     {:kind :icon-or-fill :x (+ x 2) :y (+ y 2) :w 16 :h 16 :texture skill-icon :fallback-color 0xFF2A2A2A}
    {:kind :progress-ring
      :x x :y y :size 20
      :segments max-progress-segments
      :filled-segments (int (max 0 (min max-progress-segments progress-segments)))
      :progress exp}
     {:kind :text :x (+ x 24) :y (+ y 6) :text (str skill-name) :color 0xFFFFFFFF}]))

(defn build-draw-ops
  "Build draw ops for the generic hosted skill tree screen."
  [_mouse-x _mouse-y]
  (if-let [render-data (build-screen-render-data)]
    (let [ability (:ability-info render-data)
          header [{:kind :fill :x 0 :y 0 :w 420 :h 260 :color 0xA0101010}
                  {:kind :text :x 12 :y 8 :text (str "Category: " (:category-name ability)) :color 0xFFFFFFFF}
                  {:kind :text :x 12 :y 22 :text (format "Level: %d" (int (or (:level ability) 0))) :color 0xFFE8E8E8}
                  {:kind :text :x 12 :y 36 :text (format "CP: %.0f / %.0f"
                                                          (double (or (get-in ability [:cp :cur]) 0.0))
                                                          (double (or (get-in ability [:cp :max]) 0.0)))
                   :color 0xFFAED7FF}
                  {:kind :text :x 12 :y 50 :text (format "Overload: %.0f / %.0f"
                                                          (double (or (get-in ability [:overload :cur]) 0.0))
                                                          (double (or (get-in ability [:overload :max]) 0.0)))
                   :color 0xFFFFB8A6}]
          level-up (when (get-in render-data [:ability-info :can-level-up])
                     [{:kind :fill :x 10 :y 200 :w 80 :h 20 :color 0xAA22AA22}
                      {:kind :text :x 18 :y 206 :text "Level Up" :color 0xFFFFFFFF}])
          connection-ops (mapcat line->fill-ops (or (:connections render-data) []))
          nodes (mapcat node-ops (or (:skill-nodes render-data) []))
          hover-id (:hover-skill render-data)
          hover-node (when hover-id
                       (first (filter #(= (:skill-id %) hover-id) (:skill-nodes render-data))))
          tooltip (when hover-node
                    [{:kind :fill :x 230 :y 8 :w 180 :h 68 :color 0xC0202020}
                     {:kind :text :x 236 :y 14 :text (str (:skill-name hover-node)) :color 0xFFFFFFFF}
                     {:kind :text :x 236 :y 28 :text (str (:skill-description hover-node)) :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 42 :text (format "Progress: %d%%"
                                                             (int (Math/round (* 100.0 (double (:exp hover-node))))))
                      :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 56 :text (if (:learned hover-node) "Learned" "Not learned")
                      :color (if (:learned hover-node) 0xFF88FF88 0xFFFF8888)}])]
      (vec (concat header level-up connection-ops nodes tooltip)))
    []))
