(ns cn.li.ac.ability.client.screens.skill-tree
  "Skill tree screen logic (AC layer - no Minecraft imports)."
  (:require 
[cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.registry.skill-query :as skill]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.mcmod.i18n :as i18n]))

(defn- check-learn-conditions
  [skill-id ability-data player-level developer-type]
  (if-let [skill-spec (skill-registry/get-skill skill-id)]
    (learning-rules/check-all-conditions skill-spec ability-data player-level developer-type)
    {:pass? false :failures [{:type :unknown-skill :skill-id skill-id}]}))

(defn- can-level-up-ability?
  [ability-data]
  (let [level (:level ability-data)
        cat-id (:category-id ability-data)]
    (and (< level (cfg/max-level))
         (some? cat-id)
         (let [skills (skill/get-controllable-skills-at-level cat-id level)
               cat-rate (category/get-prog-incr-rate cat-id)]
           (learning-rules/can-level-up? ability-data
                                         skills
                                         cat-rate
                                         (cfg/prog-incr-rate)
                                         (cfg/max-level))))))

;; Screen state (no Minecraft imports)
(def ^:private default-screen-state
  {:hover-skill nil
   :player-uuid nil
   :learn-context nil})

(def screen-id :skill-tree)

(defn screen-owner-key
  [owner]
  (read-model/owner-key owner :skill-tree))

(defn- with-screen-player-state-owner
  [owner f]
  (read-model/with-player-state-owner (screen-owner-key owner) f))

(defn- get-screen-player-state
  [owner]
  (read-model/get-player-state (screen-owner-key owner)))

(defn- ensure-screen-player-state!
  [owner]
  (read-model/ensure-player-state! (screen-owner-key owner)))

(defn screen-state-snapshot
  ([owner]
   (managed-screens/screen-state screen-id (screen-owner-key owner) default-screen-state)))

(defn- swap-screen-state!
  [owner f & args]
  (let [owner-key (screen-owner-key owner)]
    (apply managed-screens/update-screen-state! screen-id owner-key default-screen-state f args)))

(defn reset-screen-states-for-test!
  []
  (managed-screens/reset-managed-screen-state-for-test!)
  nil)

(def ^:private max-progress-segments 24)

;; ============================================================================
;; Font options — matching AcademyCraft SkillTree.scala:181-190
;; ============================================================================

(def fo-skill-title      {:font :ac-bold  :font-size 12 :align :center})
(def fo-skill-desc       {:font :ac-normal :font-size 9  :align :center})
(def fo-skill-prog       {:font :ac-normal :font-size 8  :align :center :color 0xFFa1e1ff})
(def fo-skill-unlearned  {:font :ac-normal :font-size 10 :align :center :color 0xFFff5555})
(def fo-skill-unlearned2 {:font :ac-normal :font-size 10 :align :center :color 0xAAffffff})
(def fo-skill-req        {:font :ac-normal :font-size 9  :align :right  :color 0xAAffffff})
(def fo-skill-req-detail  {:font :ac-normal :font-size 9  :align :left   :color 0xEEffffff})
(def fo-skill-req-detail2 {:font :ac-normal :font-size 9  :align :left   :color 0xFFee5858})
(def fo-level-title      {:font :ac-bold  :font-size 12 :align :center})
(def fo-level-req        {:font :ac-normal :font-size 9  :align :center})

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
                locked? (not (:pass? (check-learn-conditions
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
        conditions (check-learn-conditions
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

(defn- resolve-category
  [ability-data]
  (when-let [cat-id (:category-id ability-data)]
    (category/get-category cat-id)))

(defn build-ability-info-render-data
  "Build render data for ability info panel."
  [player-state]
  (let [ability-data (:ability-data player-state)
        resource-data (:resource-data player-state)
        category (resolve-category ability-data)]
    {:category-name (or (when category (translate-field category :name-key nil))
                        "Unknown")
     :level (:level ability-data)
     :cp {:cur (:cur-cp resource-data)
          :max (:max-cp resource-data)}
     :overload {:cur (:cur-overload resource-data)
                :max (:max-overload resource-data)}
    :can-level-up (can-level-up-ability? ability-data)}))

(defn build-render-data-for-player-state
  "Build skill-tree render data from projected player-state (embedded GUIs, tests)."
  [player-state developer-type & [{:keys [hover-skill]}]]
  (when player-state
    (let [ability-data (:ability-data player-state)
          cat-id (:category-id ability-data)
          category (when cat-id (category/get-category cat-id))
          skills (when cat-id (skill/get-skills-for-category cat-id))
          positions (when skills (calculate-skill-positions skills))
          dev-type (or developer-type :normal)]
      {:ability-info (build-ability-info-render-data player-state)
       :category-color (:color category)
       :skill-nodes (when positions
                      (mapv #(build-skill-node-render-data % player-state dev-type) positions))
       :connections (when positions
                     (build-skill-connections positions player-state dev-type))
       :hover-skill hover-skill})))

(defn build-screen-render-data
  "Build complete screen render data. Called by forge layer."
  [owner]
  (let [state (screen-state-snapshot owner)
        owner-key (screen-owner-key owner)]
    (when-let [_player-uuid (:player-uuid state)]
      (when-let [player-state (and owner-key (get-screen-player-state owner-key))]
        (build-render-data-for-player-state
          player-state
          (:developer-type (:learn-context state))
          {:hover-skill (:hover-skill state)})))))

;; ============================================================================
;; Event Handlers (Called by Forge Layer)
;; ============================================================================

(defn on-skill-click
  "Handle skill node click. Attempts to learn the skill if conditions are met."
  [owner skill-id]
  (let [state (screen-state-snapshot owner)
        owner-key (screen-owner-key owner)]
  (when-let [_player-uuid (:player-uuid state)]
    (when-let [player-state (and owner-key (get-screen-player-state owner-key))]
      (let [ability-data (:ability-data player-state)
            ctx (:learn-context state)
            dev-type (or (:developer-type ctx) :normal)
            conditions (check-learn-conditions
                           skill-id
                           ability-data
                           (:level ability-data)
                           dev-type)
            pos-extra (when (every? number? [(:pos-x ctx) (:pos-y ctx) (:pos-z ctx)])
                        (select-keys ctx [:pos-x :pos-y :pos-z]))]
        (when (:pass? conditions)
          (api/req-learn-skill! skill-id pos-extra nil)))))))

(defn on-level-up-click
  "Handle level-up button click (terminal skill tree — instant request)."
  [owner]
  (api/req-level-up! owner))

(defn handle-screen-click!
  "Handle clicks inside the skill tree screen using current render data."
  [owner mouse-x mouse-y]
  (if-let [render-data (build-screen-render-data owner)]
    (let [clicked? (atom false)]
      (doseq [node (:skill-nodes render-data)]
        (when (and node (not @clicked?))
          (let [dx (- mouse-x (:x node))
                dy (- mouse-y (:y node))
                dist-sq (+ (* dx dx) (* dy dy))]
            (when (< dist-sq 400)
              (on-skill-click owner (:skill-id node))
              (reset! clicked? true)))))

      (when (and (not @clicked?)
                 (get-in render-data [:ability-info :can-level-up])
                 (>= mouse-x 10) (<= mouse-x 90)
                 (>= mouse-y 200) (<= mouse-y 220))
        (on-level-up-click owner)
        (reset! clicked? true))

      (boolean @clicked?))
    false))

(defn on-mouse-move
  "Handle mouse movement for hover detection."
  [owner mouse-x mouse-y]
  (let [render-data (build-screen-render-data owner)
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
    (swap-screen-state! owner assoc :hover-skill (:skill-id hovered))))

(defn open-screen!
  "Open skill tree screen. Returns command for forge layer.
  Optional `learn-context`: `{:pos-x :pos-y :pos-z :developer-type}` from Ability Developer."
  ([owner]
   (open-screen! owner nil))
  ([owner learn-context]
    ;; Ensure player state exists before opening the UI.
    (let [owner-key (screen-owner-key owner)
          player-uuid (nth owner-key 2)]
      (ensure-screen-player-state! owner)
      (managed-screens/set-active-owner! screen-id owner-key)
      (swap-screen-state! owner merge default-screen-state
                          {:player-uuid player-uuid
                           :learn-context learn-context}))
    {:command :open-screen
     :screen-type :skill-tree}))

(defn close-screen!
  "Close skill tree screen and clean up state."
  ([owner]
   (managed-screens/clear-screen-state! screen-id (screen-owner-key owner))))

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
     {:kind :text :x (+ x 24) :y (+ y 6) :text (str skill-name) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF}]))

(defn build-draw-ops
  "Build draw ops for the generic hosted skill tree screen."
  [owner _mouse-x _mouse-y]
  (if-let [render-data (build-screen-render-data owner)]
    (let [ability (:ability-info render-data)
          header [{:kind :fill :x 0 :y 0 :w 420 :h 260 :color 0xA0101010}
                  {:kind :text :x 12 :y 8 :text (str "Category: " (:category-name ability)) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF}
                  {:kind :text :x 12 :y 22 :text (format "Level: %d" (int (or (:level ability) 0))) :font :ac-normal :font-size 9 :align :left :color 0xFFE8E8E8}
                  {:kind :text :x 12 :y 36 :text (format "CP: %.0f / %.0f"
                                                          (double (or (get-in ability [:cp :cur]) 0.0))
                                                          (double (or (get-in ability [:cp :max]) 0.0)))
                   :font :ac-normal :font-size 9 :align :left :color 0xFFAED7FF}
                  {:kind :text :x 12 :y 50 :text (format "Overload: %.0f / %.0f"
                                                          (double (or (get-in ability [:overload :cur]) 0.0))
                                                          (double (or (get-in ability [:overload :max]) 0.0)))
                   :font :ac-normal :font-size 9 :align :left :color 0xFFFFB8A6}]
          level-up (when (get-in render-data [:ability-info :can-level-up])
                     [{:kind :fill :x 10 :y 200 :w 80 :h 20 :color 0xAA22AA22}
                      {:kind :text :x 18 :y 206 :text "Level Up" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF}])
          connection-ops (mapcat line->fill-ops (or (:connections render-data) []))
          nodes (mapcat node-ops (or (:skill-nodes render-data) []))
          hover-id (:hover-skill render-data)
          hover-node (when hover-id
                       (first (filter #(= (:skill-id %) hover-id) (:skill-nodes render-data))))
          tooltip (when hover-node
                    [{:kind :fill :x 230 :y 8 :w 180 :h 68 :color 0xC0202020}
                     {:kind :text :x 236 :y 14 :text (str (:skill-name hover-node)) :font :ac-bold :font-size 12 :align :left :color 0xFFFFFFFF}
                     {:kind :text :x 236 :y 28 :text (str (:skill-description hover-node)) :font :ac-normal :font-size 9 :align :left :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 42 :text (format "Progress: %d%%"
                                                             (int (Math/round (* 100.0 (double (:exp hover-node))))))
                      :font :ac-normal :font-size 8 :align :left :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 56 :text (if (:learned hover-node) "Learned" "Not learned")
                      :font :ac-normal :font-size 8 :align :left
                      :color (if (:learned hover-node) 0xFF88FF88 0xFFFF8888)}])]
      (vec (concat header level-up connection-ops nodes tooltip)))
    []))


