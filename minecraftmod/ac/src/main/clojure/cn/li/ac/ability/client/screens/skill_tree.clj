(ns cn.li.ac.ability.client.screens.skill-tree
  "Skill tree screen logic (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.client-api :as api]
            [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.model.ability-data :as adata]
            [cn.li.ac.ability.player-state :as ps]))

;; Screen state (no Minecraft imports)
(defonce ^:private screen-state
  (atom {:hover-skill nil
         :player-uuid nil
         :learn-context nil}))

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
        skill-exp (adata/get-skill-exp ability-data skill-id)]
    {:x x
     :y y
     :learned learned?
     :can-learn (:pass? conditions)
     :conditions (:failures conditions)
     :skill-id skill-id
     :skill-name (or (:name skill) (:name-key skill) (name skill-id))
     :skill-icon (skill/get-skill-icon-path skill-id)
     :skill-level (:level skill)
     :exp (or skill-exp 0.0)}))

(defn build-ability-info-render-data
  "Build render data for ability info panel."
  [player-state]
  (let [ability-data (:ability-data player-state)
        resource-data (:resource-data player-state)
        category (:category ability-data)]
    {:category-name (or (:name category) "Unknown")
     :level (:level ability-data)
     :cp {:cur (:cur-cp resource-data)
          :max (:max-cp resource-data)}
     :overload {:cur (:cur-overload resource-data)
                :max (:max-overload resource-data)}
     :can-level-up (learning/can-level-up? ability-data)}))

(defn build-screen-render-data
  "Build complete screen render data. Called by forge layer."
  []
  (when-let [player-uuid (:player-uuid @screen-state)]
    (when-let [player-state (ps/get-player-state player-uuid)]
      (let [ability-data (:ability-data player-state)
            category (:category ability-data)
            skills (when category
                    (skill/get-skills-for-category (:category-id category)))
            positions (when skills
                       (calculate-skill-positions skills))
            dev-type (or (:developer-type (:learn-context @screen-state)) :normal)]
        {:ability-info (build-ability-info-render-data player-state)
         :category-color (:color category)
         :skill-nodes (when positions
                       (mapv #(build-skill-node-render-data % player-state dev-type) positions))
         :connections (when positions
            (build-skill-connections positions player-state dev-type))
         :hover-skill (:hover-skill @screen-state)}))))

;; ============================================================================
;; Event Handlers (Called by Forge Layer)
;; ============================================================================

(defn on-skill-click
  "Handle skill node click. Attempts to learn the skill if conditions are met."
  [skill-id]
  (when-let [player-uuid (:player-uuid @screen-state)]
    (when-let [player-state (ps/get-player-state player-uuid)]
      (let [ability-data (:ability-data player-state)
            ctx (:learn-context @screen-state)
            dev-type (or (:developer-type ctx) :normal)
            conditions (learning/check-all-conditions
                           skill-id
                           ability-data
                           (:level ability-data)
                           dev-type)
            pos-extra (when (every? number? [(:pos-x ctx) (:pos-y ctx) (:pos-z ctx)])
                        (select-keys ctx [:pos-x :pos-y :pos-z]))]
        (when (:pass? conditions)
          (api/req-learn-skill! skill-id pos-extra nil))))))

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
    (swap! screen-state assoc :hover-skill (:skill-id hovered))))

(defn open-screen!
  "Open skill tree screen. Returns command for forge layer.
  Optional `learn-context`: `{:pos-x :pos-y :pos-z :developer-type}` from Ability Developer."
  ([player-uuid]
   (open-screen! player-uuid nil))
  ([player-uuid learn-context]
    ;; 确保player-state存在，防止UI卡死
    (ps/get-or-create-player-state! player-uuid)
    (swap! screen-state assoc :player-uuid player-uuid :learn-context learn-context)
    {:command :open-screen
     :screen-type :skill-tree}))

(defn close-screen!
  "Close skill tree screen and clean up state."
  []
  (swap! screen-state assoc :player-uuid nil :hover-skill nil :learn-context nil))
