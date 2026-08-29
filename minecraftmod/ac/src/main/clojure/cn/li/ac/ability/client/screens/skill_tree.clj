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
   [cn.li.ac.config.modid :as modid]
   [cn.li.mcmod.i18n :as i18n]
   [cn.li.ac.gui.presentation :as presentation]))

;; Presentation state helpers
(declare ensure-screen-player-state! swap-screen-state!)
(defn- clamp01 [v] (max 0.0 (min 1.0 (double v))))

;; ============================================================================
;; Constants (matching upstream SkillTree.scala)
;; ============================================================================
(def ^:private max-progress-segments 24)
;; Shared skill-node rendering constants — also used by block/developer/panel.clj

;; ============================================================================
;; Condition checking
;; ============================================================================
(defn- check-learn-conditions
  [skill-id ability-data player-level developer-type]
  (if-let [skill-spec (skill-registry/get-skill skill-id)]
    (learning-rules/check-all-conditions skill-spec ability-data player-level developer-type)
    {:pass? false :failures [{:type :unknown-skill :skill-id skill-id}]}))

(defn- can-level-up-ability?
  [ability-data]
  (let [level (:level ability-data) cat-id (:category-id ability-data)]
    (and (< level (cfg/max-level)) (some? cat-id)
         (let [skills (skill/get-controllable-skills-at-level cat-id level)
               cat-rate (category/get-prog-incr-rate cat-id)]
           (learning-rules/can-level-up? ability-data skills cat-rate
                                         (cfg/prog-incr-rate) (cfg/max-level))))))

;; ============================================================================
;; Screen state
;; ============================================================================
(def ^:private default-screen-state
  {:selected-skill nil :player-uuid nil :learn-context nil})

(def screen-id :skill-tree)

(defn screen-owner-key [owner] (read-model/owner-key owner :skill-tree))
(defn- get-screen-player-state [owner] (read-model/get-player-state (screen-owner-key owner)))
(defn- ensure-screen-player-state! [owner] (read-model/ensure-player-state! (screen-owner-key owner)))
(defn screen-state-snapshot [owner]
  (managed-screens/screen-state screen-id (screen-owner-key owner) default-screen-state))
(defn- swap-screen-state! [owner f & args]
  (let [owner-key (screen-owner-key owner)]
    (apply managed-screens/update-screen-state! screen-id owner-key default-screen-state f args)))
(defn reset-screen-states-for-test! [] (managed-screens/reset-managed-screen-state-for-test!) nil)

;; ============================================================================
;; Text data helpers
;; ============================================================================
(defn- translate-field [spec text-key fallback]
  (if-let [key-name (get spec text-key)] (i18n/translate key-name) fallback))

;; ============================================================================
;; Layout Calculations
;; ============================================================================
(defn calculate-skill-positions [skills]
  (let [ordered (vec (sort-by #(vector (get % :level) (get % :id)) skills))
        cx 200 cy 120 radius 80
        n (max 1 (count ordered))]
    (map-indexed (fn [idx s]
                   (if-let [[px py] (:ui-position s)]
                     {:skill s :x (int px) :y (int py) :idx idx}
                     (let [angle (* idx (/ (* 2 Math/PI) n))]
                       {:skill s :x (int (+ cx (* radius (Math/cos angle))))
                        :y (int (+ cy (* radius (Math/sin angle)))) :idx idx})))
                 ordered)))

(defn- build-skill-connections [skill-positions player-state developer-type]
  (let [ad (:ability-data player-state)
        by-id (into {} (map (fn [s] [(:id (:skill s)) s]) skill-positions))]
    (vec (remove nil? (apply concat
          (map (fn [{:keys [skill x y idx] :as node}]
                 (let [tid (:id skill)
                       locked? (not (:pass? (check-learn-conditions tid ad (:level ad) developer-type)))]
                   (for [{sid :skill-id me :min-exp} (:prerequisites skill)
                         :let [{fx :x fy :y} (get by-id sid)
                               child-learned? (adata/is-learned? ad tid)
                               parent-learned? (adata/is-learned? ad sid)]
                         :when fx]
                     {:from-x (+ fx 8) :from-y (+ fy 8) :to-x (+ x 8) :to-y (+ y 8)
                      :satisfied? (>= (or (adata/get-skill-exp ad sid) 0.0) (double me))
                      :locked? locked? :child-learned? child-learned?
                      :child-idx (or (:idx (get by-id tid)) idx)
                      :m-alpha (cond child-learned? 1.0 (empty? (:prerequisites skill)) 0.7 parent-learned? 0.7 :else 0.25)})))
               skill-positions))))))

;; ============================================================================
;; Render Data Builders
;; ============================================================================
(defn build-skill-node-render-data [skill-pos player-state developer-type]
  (let [{:keys [skill x y idx]} skill-pos
        sid (or (:skill-id skill) (:id skill))
        ad (:ability-data player-state)
        learned? (adata/is-learned? ad sid)
        conds (check-learn-conditions sid ad (:level ad) developer-type)
        exp (double (or (adata/get-skill-exp ad sid) 0.0))
        prog (clamp01 exp)
        ;; Upstream treats "no parent" as parent-learned, both for mAlpha and
        ;; for canBePotentiallyLearned.
        parent-learned? (let [pid (some-> (:prerequisites skill) first :skill-id)]
                          (or (nil? pid) (adata/is-learned? ad pid)))
        m-alpha (cond learned? 1.0
                      parent-learned? 0.7
                      :else 0.25)]
    {:x x :y y :idx idx :learned learned? :can-learn (:pass? conds)
     :conditions (:failures conds) :skill-id sid
     :skill-name (or (:name skill) (translate-field skill :name-key (name sid)) (name sid))
     :skill-description (translate-field skill :description-key "")
     :skill-icon (skill/get-skill-icon-path sid)
     :skill-level (:level skill) :exp prog :m-alpha m-alpha
     :parent-learned? parent-learned?
     :progress-segments (int (Math/round (double (* prog max-progress-segments))))}))

(defn- resolve-category [ad] (when-let [cid (:category-id ad)] (category/get-category cid)))

(defn build-ability-info-render-data [ps]
  (let [ad (:ability-data ps) rd (:resource-data ps) cat (resolve-category ad)]
    {:category-name (or (when cat (translate-field cat :name-key nil)) "Unknown")
     :level (:level ad) :cp {:cur (:cur-cp rd) :max (:max-cp rd)}
     :overload {:cur (:cur-overload rd) :max (:max-overload rd)}
     :can-level-up (can-level-up-ability? ad)}))

(defn potentially-learnable?
  "Upstream LearningHelper.canBePotentiallyLearned, which decides what the tree
   shows at all:

     level >= skill.level || isLearned(skill)
       || skill.parent == null || isLearned(skill.parent)

   An OR chain, so a skill above your level still shows once its parent is
   learned. Everything it admits is drawn, and everything drawn is clickable --
   the detail panel exists precisely to spell out what a dimmed node still
   needs (upstream's foSkillReq / foSkillReqDetail)."
  [node]
  (boolean
    (or (not (:locked? node))
        (:learned node)
        (:parent-learned? node))))

(defn build-render-data-for-player-state [ps dev-type]
  (when ps
    (let [ad (:ability-data ps) cid (:category-id ad)
          cat (when cid (category/get-category cid))
          skills (when cid
                   (filter #(get % :enabled) (skill/get-skills-for-category cid)))
          pos (when skills (calculate-skill-positions skills))]
      {:ability-info (build-ability-info-render-data ps) :category-color (:color cat)
       :skill-nodes (when pos
                      (->> pos
                           (mapv (fn [p]
                                   (let [n (build-skill-node-render-data p ps (or dev-type :normal))]
                                     (assoc n :locked?
                                            (> (:skill-level n) (:level (:ability-data ps)))))))
                           (filterv potentially-learnable?)))
       :connections (when pos (build-skill-connections pos ps (or dev-type :normal)))
       })))

(defn build-screen-render-data [owner]
  (let [st (screen-state-snapshot owner) ok (screen-owner-key owner)]
    (when-let [_pu (:player-uuid st)]
      (when-let [ps (and ok (get-screen-player-state ok))]
        (build-render-data-for-player-state ps (:developer-type (:learn-context st)))))))

;; ============================================================================
;; Event Handlers
;; ============================================================================
(defn on-skill-click [owner sid]
  (let [st (screen-state-snapshot owner)]
    (if (= sid (:selected-skill st))
      (swap-screen-state! owner assoc :selected-skill nil)
      (swap-screen-state! owner assoc :selected-skill sid))))


(defn open-screen!
  ([owner] (open-screen! owner nil))
  ([owner lc]
   (let [ok (screen-owner-key owner) pu (nth ok 2)]
     (ensure-screen-player-state! owner)
     (managed-screens/set-active-owner! screen-id ok)
     (swap-screen-state! owner merge default-screen-state
                         {:player-uuid pu :learn-context lc}))
   {:command :open-screen :screen-type :skill-tree}))

(defn close-screen! [owner]
  (managed-screens/clear-screen-state! screen-id (screen-owner-key owner)))

(defn- skill-icon-src [icon]
  (if (and (string? icon) (seq icon) (not (.contains ^String icon ":")))
    (modid/namespaced-path icon)
    icon))

(defn- skill-tree-composite [nodes connections selected]
  (let [scale-x 0.62 scale-y 0.52
        node-items
        (mapcat (fn [{:keys [skill-id skill-name skill-icon x y learned exp]}]
                  (let [px (+ 12.0 (* scale-x (double (or x 0.0))))
                        py (+ 8.0 (* scale-y (double (or y 0.0))))
                        selected? (= skill-id selected)
                        color (if selected? 0xFF68B5FF (if learned 0xFF65D58A 0xFF626A78))]
                    (vec (remove nil?
                           [{:kind :quad :x px :y py :w 18 :h 18 :rgba color}
                            (when skill-icon {:kind :image :src (skill-icon-src skill-icon) :x (+ px 2) :y (+ py 2) :w 14 :h 14})
                            {:kind :text :text (str (or skill-name (name skill-id)) " "
                                                    (format "%.0f%%" (* 100.0 (double (or exp 0.0)))))
                             :x (+ px 21) :y (+ py 4) :rgba 0xFFFFFFFF}]))))
                nodes)
        connection-items
        (mapcat (fn [{:keys [from-x from-y to-x to-y]}]
                  (let [x1 (+ 21.0 (* scale-x (double (or from-x 0.0))))
                        y1 (+ 17.0 (* scale-y (double (or from-y 0.0))))
                        x2 (+ 21.0 (* scale-x (double (or to-x 0.0))))
                        y2 (+ 17.0 (* scale-y (double (or to-y 0.0))))
                        x (min x1 x2) y (min y1 y2)
                        w (max 1.0 (Math/abs (- x2 x1)))
                        h (max 1.0 (Math/abs (- y2 y1)))]
                    [{:kind :quad :x x :y y :w w :h h :rgba 0x6688AACC}]))
                connections)]
    (vec (concat [{:kind :quad :x 4 :y 4 :w 300 :h 108 :rgba 0xAA10151F}]
                 connection-items node-items))))

(defn- selected-node [nodes selected]
  (some #(when (= selected (:skill-id %)) %) nodes))

(defn- presentation-state [owner]
  (let [data (or (build-screen-render-data owner) {})
        info (:ability-info data)
        nodes (vec (:skill-nodes data))
        selected (:selected-skill (screen-state-snapshot owner))
        selected-data (selected-node nodes selected)
        skill-items (mapv (fn [{:keys [skill-id learned exp skill-name can-learn]}]
                            {:kind :skill :skill-id skill-id
                             :label (str (if learned "[learned] " "[ ] ")
                                         (or skill-name (name skill-id)) " "
                                         (format "%.0f%%" (* 100.0 (double (or exp 0.0)))))
                             :action-label (if (= skill-id selected) "Learn/View" "Select")
                             :learned? (boolean learned) :can-learn? (boolean can-learn)})
                          nodes)
        level-item (when (get info :can-level-up)
                     [{:kind :level-up :label "Ability level-up available"
                       :action-label "Level Up"}])
        detail (if selected-data
                 {:title (str (:skill-name selected-data))
                  :level (str "Required level: " (:skill-level selected-data))
                  :description (str (or (:skill-description selected-data) ""))
                  :status (if (:learned selected-data) "Learned"
                              (if (:can-learn selected-data) "Ready to learn" "Locked"))
                  :learn-label (if (:learned selected-data) "Learned" "Learn")}
                 {:title "Select a skill" :level "" :description ""
                  :status "Select a node to view details" :learn-label "Learn"})]
    {:title "Skill Tree"
     :category (str "Category: " (or (:category-name info) "unknown"))
     :level (str "Level: " (or (:level info) 0))
     :items (vec (concat level-item skill-items))
     :selected (double (or (some->> skill-items
                                    (keep-indexed (fn [i item]
                                                   (when (= (:skill-id item) selected) i)))
                                    first) 0))
     :selected-skill (str (or selected ""))
     :detail detail
     :composite-list (skill-tree-composite nodes (:connections data) selected)
     :status "Select a skill; activate it again to learn"
     :button-left "Refresh" :button-right "Refresh"}))

(defn- request-learn! [owner sid callback]
  (let [st (screen-state-snapshot owner)
        ok (screen-owner-key owner)
        ps (and (:player-uuid st) (get-screen-player-state ok))
        ad (:ability-data ps)
        dt (or (:developer-type (:learn-context st)) :normal)
        checks (when ad (check-learn-conditions sid ad (:level ad) dt))
        ctx (:learn-context st)
        extra (when (and ctx (every? number? [(:pos-x ctx) (:pos-y ctx) (:pos-z ctx)]))
                (select-keys ctx [:pos-x :pos-y :pos-z]))]
    (when (and ps (:pass? checks))
      (api/req-learn-skill! owner sid extra callback))))
(defn- present! [mount owner]
  (when-let [present (:present! mount)]
    (present (presentation-state owner))))

(defn open-presentation! [player-uuid & [learn-context]]
  (let [owner (read-model/local-client-owner player-uuid "skill-tree")
        mount* (atom nil)]
    (open-screen! owner learn-context)
    (let [vm (presentation/mount-view!
               {:view-id :academy.app/skill-tree :host-kind :screen
                :state (presentation-state owner)
                :dispatch-action!
                (fn [action payload _current]
                  (let [item (:item payload)
                        selected (:selected-skill (screen-state-snapshot owner))
                        sid (or (:skill-id item) selected)
                        refresh #(present! @mount* owner)]
                    (case action
                      :skill-tree/select
                      (do (on-skill-click owner sid) (presentation-state owner))
                      :skill-tree/learn
                      (if (= :level-up (:kind item))
                        (do (api/req-level-up! owner (fn [_] (refresh)))
                            (presentation-state owner))
                        (do (when sid
                            (if (= sid selected)
                              (let [node (some #(when (= sid (:skill-id %)) %)
                                               (:skill-nodes (or (build-screen-render-data owner) {})))]
                                (when (:can-learn node)
                                  (request-learn! owner sid (fn [_] (refresh)))))
                              (on-skill-click owner sid)))
                          (presentation-state owner)))
                      :skill-tree/refresh
                      (presentation-state owner)
                      :skill-tree/level-up
                      (do (api/req-level-up! owner (fn [_] (refresh)))
                          (presentation-state owner))
                      (presentation-state owner))))
                :on-close #(close-screen! owner)})]
      (reset! mount* vm)
      vm)))
