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
   [cn.li.mcmod.client.platform-bridge :as client-bridge]
   [cn.li.mcmod.platform.ui :as platform-ui]
   [cn.li.mcmod.gui.cgui-core :as cgui-core]
   [cn.li.mcmod.gui.components :as comp]
   [cn.li.mcmod.gui.events :as events]
   [cn.li.mcmod.util.log :as log]))

;; Forward declares for functions used by widget factory (defined later in file)
(declare build-draw-ops build-tree-ops ensure-screen-player-state! swap-screen-state! on-mouse-move handle-screen-click! level-up-popup-ops)
(defn- now-ms [] (client-bridge/game-time-ms))
(defn- clamp01 [v] (max 0.0 (min 1.0 (double v))))
(defn- lerp [a b t] (+ a (* (- b a) (clamp01 t))))

;; ============================================================================
;; Constants (matching upstream SkillTree.scala)
;; ============================================================================
(def ^:private max-progress-segments 24)
;; Shared skill-node rendering constants — also used by block/developer/panel.clj
(def widget-size 16.0)
(def total-size 23.0)
(def prog-size 31.0)
(def icon-size 14.0)
(def draw-align (/ (- widget-size total-size) 2))   ;; -3.5
(def prog-align (/ (- total-size prog-size) 2))      ;; -4.0
(def align (/ (- total-size icon-size) 2))           ;; 4.5
(def ^:private back-scale 1.01)
(def ^:private back-scale-inv (/ 1.0 back-scale))
(def ^:private max-du-skills 10.0)

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
  {:hover-skill nil :selected-skill nil :player-uuid nil :learn-context nil
   :creation-time nil :mouse-x 0 :mouse-y 0 :hovered-skill-id nil
   :hover-node-transitions {}
   :showing-level-up-popup? false :level-up-popup-open-ms 0 :level-up-dev-state nil})

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
        m-alpha (cond learned? 1.0
                      (empty? (:prerequisites skill)) 0.7   ;; no parent = always accessible
                      (let [pid (some-> (:prerequisites skill) first :skill-id)]
                        (adata/is-learned? ad pid)) 0.7      ;; parent learned
                      :else 0.25)]
    {:x x :y y :idx idx :learned learned? :can-learn (:pass? conds)
     :conditions (:failures conds) :skill-id sid
     :skill-name (or (:name skill) (translate-field skill :name-key (name sid)) (name sid))
     :skill-description (translate-field skill :description-key "")
     :skill-icon (skill/get-skill-icon-path sid)
     :skill-level (:level skill) :exp prog :m-alpha m-alpha
     :progress-segments (int (Math/round (double (* prog max-progress-segments))))}))

(defn- resolve-category [ad] (when-let [cid (:category-id ad)] (category/get-category cid)))

(defn build-ability-info-render-data [ps]
  (let [ad (:ability-data ps) rd (:resource-data ps) cat (resolve-category ad)]
    {:category-name (or (when cat (translate-field cat :name-key nil)) "Unknown")
     :level (:level ad) :cp {:cur (:cur-cp rd) :max (:max-cp rd)}
     :overload {:cur (:cur-overload rd) :max (:max-overload rd)}
     :can-level-up (can-level-up-ability? ad)}))

(defn build-render-data-for-player-state [ps dev-type & [{:keys [hover-skill]}]]
  (when ps
    (let [ad (:ability-data ps) cid (:category-id ad)
          cat (when cid (category/get-category cid))
          skills (when cid
                   (filter #(get % :enabled) (skill/get-skills-for-category cid)))
          pos (when skills (calculate-skill-positions skills))]
      {:ability-info (build-ability-info-render-data ps) :category-color (:color cat)
       :skill-nodes (when pos (mapv (fn [p] (let [n (build-skill-node-render-data p ps (or dev-type :normal))] (assoc n :locked? (> (:skill-level n) (:level (:ability-data ps)))))) pos))
       :connections (when pos (build-skill-connections pos ps (or dev-type :normal)))
       :hover-skill hover-skill})))

(defn build-screen-render-data [owner]
  (let [st (screen-state-snapshot owner) ok (screen-owner-key owner)]
    (when-let [_pu (:player-uuid st)]
      (when-let [ps (and ok (get-screen-player-state ok))]
        (build-render-data-for-player-state ps (:developer-type (:learn-context st))
                                            {:hover-skill (:hover-skill st)})))))

;; ============================================================================
;; Event Handlers
;; ============================================================================
(defn on-skill-click [owner sid]
  (let [st (screen-state-snapshot owner)]
    (if (= sid (:selected-skill st))
      (swap-screen-state! owner assoc :selected-skill nil)
      (swap-screen-state! owner assoc :selected-skill sid))))

(defn on-skill-learn-click [owner sid]
  (let [st (screen-state-snapshot owner) ok (screen-owner-key owner)]
    (when-let [_pu (:player-uuid st)]
      (when-let [ps (and ok (get-screen-player-state ok))]
        (let [ad (:ability-data ps) ctx (:learn-context st)
              dt (or (:developer-type ctx) :normal)
              cs (check-learn-conditions sid ad (:level ad) dt)
              pe (when (every? number? [(:pos-x ctx) (:pos-y ctx) (:pos-z ctx)])
                   (select-keys ctx [:pos-x :pos-y :pos-z]))]
          (when (:pass? cs) (api/req-learn-skill! sid pe nil)))))))

(defn handle-screen-click! [owner mx my]
  (let [st (screen-state-snapshot owner)]
    (cond
      ;; Level-up popup: LEARN fires API, any other click dismisses
      (:showing-level-up-popup? st)
      (let [ds (:level-up-dev-state st)
            has-result? (some? (:result ds))
            btn-x 194 btn-y 195   ; level-up-popup-ops: cx=210 cy=130, text-base=155, btn=195, btn-x=194
            on-btn? (and (not has-result?)
                         (>= mx btn-x) (<= mx (+ btn-x 32))
                         (>= my btn-y) (<= my (+ btn-y 16)))]
        (if on-btn?
          (api/req-level-up! owner
            (fn [resp]
              (swap-screen-state! owner update :level-up-dev-state
                assoc :result (if (:success resp) :success :failed))))
          (swap-screen-state! owner assoc
            :showing-level-up-popup? false :level-up-dev-state nil))
        true)

      ;; Skill detail popup shown: cx=210, cy=130, btn at (194,202) size 32×16
      (:selected-skill st)
      (let [sel (:selected-skill st)
            cx 210 cy 130
            btn-x (- cx 16) btn-y (+ cy 72)   ; 194, 202
            btn-x2 (+ btn-x 32) btn-y2 (+ btn-y 16)
            sel-node (when-let [rd (build-screen-render-data owner)]
                       (first (filter #(= (:skill-id %) sel) (:skill-nodes rd))))]
        (cond
          (and sel-node (not (:learned sel-node))
               (>= mx btn-x) (<= mx btn-x2) (>= my btn-y) (<= my btn-y2))
          (do (on-skill-learn-click owner sel) true)
          :else (do (swap-screen-state! owner assoc :selected-skill nil) true)))

      ;; No popup open: check skill nodes and level-up button
      :else
      (if-let [rd (build-screen-render-data owner)]
        (let [c? (atom false)]
          (doseq [n (:skill-nodes rd)] (when (and n (not @c?))
            (when (< (+ (* (- mx (:x n)) (- mx (:x n))) (* (- my (:y n)) (- my (:y n)))) 400)
              (on-skill-click owner (:skill-id n)) (reset! c? true))))
          (when (and (not @c?) (get-in rd [:ability-info :can-level-up])
                     (>= mx 10) (<= mx 90) (>= my 200) (<= my 220))
            (swap-screen-state! owner assoc
              :showing-level-up-popup? true
              :level-up-popup-open-ms (now-ms)
              :level-up-dev-state {:result nil :error nil})
            (reset! c? true))
          (when (and (not @c?) (:selected-skill (screen-state-snapshot owner)))
            (swap-screen-state! owner assoc :selected-skill nil))
          (boolean @c?))
        false))))

(defn on-mouse-move [owner mx my]
  (let [rd (build-screen-render-data owner) nodes (:skill-nodes rd)
        st (screen-state-snapshot owner)
        h (when nodes (first (filter #(< (+ (* (- mx (:x %)) (- mx (:x %))) (* (- my (:y %)) (- my (:y %)))) 400) nodes)))
        new-id (:skill-id h)
        prev-id (:hovered-skill-id st)
        transitions (:hover-node-transitions st {})
        now (now-ms)]
    (if (not= new-id prev-id)
      ;; Hover changed — update immediately (upstream: per-widget FrameEvent, no global gate)
      (let [new-transitions (-> transitions
                               (cond-> prev-id (assoc prev-id {:start now :dir :out}))
                               (cond-> new-id (assoc new-id {:start now :dir :in})))]
        (swap-screen-state! owner
          (fn [st] (-> st
                      (assoc :mouse-x mx :mouse-y my
                             :hover-skill new-id :hovered-skill-id new-id
                             :hover-node-transitions new-transitions)))))
      ;; Same node — just update mouse position
      (swap-screen-state! owner assoc :mouse-x mx :mouse-y my))))

(defn open-screen!
  ([owner] (open-screen! owner nil))
  ([owner lc]
   (let [ok (screen-owner-key owner) pu (nth ok 2)]
     (ensure-screen-player-state! owner)
     (managed-screens/set-active-owner! screen-id ok)
     (swap-screen-state! owner merge default-screen-state
                         {:player-uuid pu :learn-context lc :creation-time (now-ms)}))
   {:command :open-screen :screen-type :skill-tree}))

(defn close-screen! [owner]
  (managed-screens/clear-screen-state! screen-id (screen-owner-key owner)))

;; ============================================================================
;; CGui Widget Factory — replaces managed-screen dispatch for :ac/skill-tree
;; ============================================================================

(defn- resolve-owner [payload]
  (let [sid (:client-session-id payload "")
        pu  (:player-uuid payload)]
    {:client-session-id sid :player-uuid pu}))

(defn- skill-tree-mouse-pos [root]
  (let [m (get @(:metadata root) :mouse-pos)]
    (or m [210 130])))

(defn- skill-tree-mouse-pos! [root pos]
  (swap! (:metadata root) assoc :mouse-pos pos))

(defn- skill-tree-draw-ops [root]
  (let [d (get @(:metadata root) :draw-ops)]
    (or d [])))

(defn- skill-tree-draw-ops! [root ops]
  (swap! (:metadata root) assoc :draw-ops ops))

(defn create-skill-tree-widget
  "Create CGui widget hosting skill-tree draw-ops. Factory for :ac/skill-tree.
  Static/dynamic separation: node/connection draw-ops cached without parallax;
  only bg-UV and global translate recomputed on mouse move."
  [{:keys [player-uuid client-session-id learn-context] :as payload}]
  (let [owner (resolve-owner payload)
        ok    (screen-owner-key owner)
        pu    (or player-uuid (nth ok 2))
        root  (cgui-core/create-container :name "skill-tree-root" :pos [0 0] :size [420 260])
        ;; Dirty-check + static caches
        last-state-key (atom nil)
        last-mouse-pos (atom [210 130])
        static-tree-ops (atom [])       ;; nodes + connections + popups, no parallax
        static-pre-tree-ops (atom [])]  ;; header + level-up button
    ;; initialize managed-screen state (shared with existing code)
    (ensure-screen-player-state! owner)
    (managed-screens/set-active-owner! screen-id ok)
    (swap-screen-state! owner merge default-screen-state
                        {:player-uuid pu :learn-context learn-context :creation-time (now-ms)})
    ;; frame → hybrid: static cache rebuild on state change, lightweight assembly always
    (events/on-frame root
      (fn [_]
        (let [[rw rh] (cgui-core/get-size root)
              [mx my] (skill-tree-mouse-pos root)
              st (screen-state-snapshot owner)
              state-key {:hovered-skill-id (:hovered-skill-id st)
                         :selected-skill (:selected-skill st)
                         :showing-level-up-popup? (:showing-level-up-popup? st)
                         :level-up-dev-state (:level-up-dev-state st)
                         :hover-node-transitions (:hover-node-transitions st)}
              state-changed? (not= state-key @last-state-key)]
          ;; === Branch 1: state changed → rebuild static caches (rare, <1Hz) ===
          (when state-changed?
            (reset! last-state-key state-key)
            (if-let [rd (build-screen-render-data owner)]
              (let [ab (:ability-info rd)
                    anim (if-let [ct (:creation-time st)]
                           (/ (- (now-ms) ct) 1000.0) 5.0)
                    ;; Static tree: nodes + connections + tooltip + popups, no parallax
                    tree (build-tree-ops rd anim 0.5 0.5
                           (:hovered-skill-id st) (:hover-node-transitions st {})
                           (:selected-skill st) :static? true)
                    ;; Pre-tree: header overlay + texts + level-up button
                    pre-tree (persistent!
                               (let [out (transient [{:kind :fill :x 0 :y 0 :w 420 :h 260 :color 0xA0101010}
                                                     {:kind :text :x 12 :y 8  :text (str "Category: " (:category-name ab)) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF}
                                                     {:kind :text :x 12 :y 22 :text (format "Level: %d" (int (or (:level ab) 0))) :font :ac-normal :font-size 9 :align :left :color 0xFFE8E8E8}
                                                     {:kind :text :x 12 :y 36 :text (format "CP: %.0f / %.0f" (double (get-in ab [:cp :cur] 0.0)) (double (get-in ab [:cp :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFAED7FF}
                                                     {:kind :text :x 12 :y 50 :text (format "Overload: %.0f / %.0f" (double (get-in ab [:overload :cur] 0.0)) (double (get-in ab [:overload :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFFFB8A6}])]
                                 (when (and (:can-level-up ab) (not (:showing-level-up-popup? st)))
                                   (conj! out {:kind :fill :x 10 :y 200 :w 80 :h 20 :color 0xAA22AA22})
                                   (conj! out {:kind :text :x 18 :y 206 :text "Level Up" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF}))
                                 out))
                    ;; Post-tree: level-up popup (if showing)
                    post-tree (when (:showing-level-up-popup? st)
                                (let [open-ms (:level-up-popup-open-ms st 0)
                                      popup-anim (/ (- (now-ms) open-ms) 1000.0)
                                      current-level (int (or (:level ab) 1))
                                      target-level (inc current-level)
                                      cond-icon (modid/asset-path "textures"
                                                  (str "abilities/condition/any" target-level ".png"))]
                                  (level-up-popup-ops target-level cond-icon popup-anim
                                    {:dev-state (:level-up-dev-state st)
                                     :screen-w 420 :screen-h 260})))]
                (reset! static-tree-ops
                  (if (seq post-tree)
                    (persistent! (let [out (transient tree)]
                                  (doseq [op post-tree] (conj! out op))
                                  out))
                    tree))
                (reset! static-pre-tree-ops pre-tree))
              (do (reset! static-tree-ops [])
                  (reset! static-pre-tree-ops []))))
          ;; === Branch 2: always — assemble final ops (lightweight) ===
          (let [safe-w (max 1 rw) safe-h (max 1 rh)
                mx01 (clamp01 (/ (double mx) (max 1.0 (double safe-w))))
                my01 (clamp01 (/ (double my) (max 1.0 (double safe-h))))
                bg-dx (* (- mx01 0.5) 0.01) bg-dy (* (- my01 0.5) 0.01)
                bg-u (+ (* (- bg-dx 0.5) back-scale-inv) 0.5)
                bg-v (+ (* (- bg-dy 0.5) back-scale-inv) 0.5)
                node-dx (* (- mx01 0.5) 10.0) node-dy (* (- my01 0.5) 10.0)
                final-ops (persistent!
                            (let [out (transient [{:kind :raw-rect-uv :texture :bg-area :x 0 :y 0 :w (int 420) :h (int 260)
                                                  :min-u (float bg-u) :max-u (float (+ bg-u back-scale-inv))
                                                  :min-v (float bg-v) :max-v (float (+ bg-v back-scale-inv))}])]
                              (doseq [op @static-pre-tree-ops] (conj! out op))
                              (conj! out {:kind :translate :x node-dx :y node-dy :z 0.0})
                              (doseq [op @static-tree-ops] (conj! out op))
                              out))]
            (reset! last-mouse-pos [mx my])
            (skill-tree-draw-ops! root final-ops)))))
    ;; frame → update hover (runs every frame — lightweight state-only check)
    (events/on-frame root
      (fn [_]
        (let [[mx my] (skill-tree-mouse-pos root)]
          (on-mouse-move owner mx my))))
    ;; click handler
    (events/on-left-click root
      (fn [evt]
        (let [mx (:mouse-x evt) my (:mouse-y evt)]
          (skill-tree-mouse-pos! root [mx my])
          (handle-screen-click! owner mx my))))
    ;; drag → track mouse
    (events/on-drag root
      (fn [evt]
        (let [[ox oy] (skill-tree-mouse-pos root)
              dx (double (:dx evt 0)) dy (double (:dy evt 0))]
          (skill-tree-mouse-pos! root [(+ ox dx) (+ oy dy)]))))
    ;; draw-ops component host
    (let [[rw rh] (cgui-core/get-size root)
          host (cgui-core/create-widget :pos [0 0] :size [rw rh])]
      (comp/add-component! host (comp/draw-ops {:ops-fn #(skill-tree-draw-ops root)}))
      (cgui-core/add-widget! root host))
    root))

(let [registered? (atom false)]
  (defn install-widget-factory!
    "Register skill-tree CGui widget factory. Idempotent."
    []
    (when (compare-and-set! registered? false true)
      (platform-ui/register-widget-factory! :ac/skill-tree create-skill-tree-widget)
      (log/info "Skill-tree widget factory registered"))))

;; ============================================================================
;; Draw Ops — Line connections (rotated-quad)
;; ============================================================================
(defn- build-line-ops [connections anim-time]
  (persistent!
    (reduce
      (fn [out {:keys [from-x from-y to-x to-y lb child-learned? m-alpha child-idx]}]
        (let [line-alpha (* (or m-alpha 0.7) (if child-learned? 1.0 0.4))
              alpha-byte (int (* 255.0 (clamp01 line-alpha)))
              color (bit-or (bit-shift-left alpha-byte 24) 0xFFFFFF)
              dx (- to-x from-x) dy (- to-y from-y)
              norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
          (if (pos? norm)
            (let [ndx (/ dx norm) ndy (/ dy norm)
                  x0 (+ from-x (* ndx 12.2)) y0 (+ from-y (* ndy 12.2))
                  x1-full (- to-x (* ndx 12.2)) y1-full (- to-y (* ndy 12.2))
                  blend (double (if (some? lb) lb 1.0))
                  x1 (lerp x0 x1-full blend)
                  y1 (lerp y0 y1-full blend)]
              (if (pos? blend)
                (conj! out {:kind :line-quad :x0 x0 :y0 y0 :x1 x1 :y1 y1
                            :line-width 5.5 :color color})
                out))
            out)))
      (transient [])
      connections)))

;; ============================================================================
;; Draw Ops — Skill nodes (textured, depth-layered, animated)
;; ============================================================================
(defn- node-ops [node anim-time hovered-id hover-transitions line-ops]
  "Build draw ops for a single skill node, matching upstream SkillTree.scala FrameEvent.
  Uses per-node hover transitions with transit gate (upstream: StateIdle↔StateHover FSM).
  Transient building — no concat/filterv overhead."
  (let [{:keys [x y idx learned skill-icon exp m-alpha skill-id]} node
        effective-m-alpha (or m-alpha 0.7)
        dt (max 0.0 (- anim-time (* idx 0.08) 0.1))
        back-alpha (* effective-m-alpha (clamp01 (* dt 10.0)))
        icon-alpha (* effective-m-alpha (clamp01 (* (- dt 0.08) 10.0)))
        progress-blend (clamp01 (* (- dt 0.12) 2.0))
        ;; Per-node hover FSM (upstream: each widget has own state/lastTransit)
        trans (get hover-transitions skill-id)
        trans-start (:start trans)
        trans-dir (:dir trans)
        trans-elapsed (if trans-start (/ (- (now-ms) trans-start) 1000.0) 2.0)
        transit (clamp01 (/ trans-elapsed 0.1))
        node-scale (case trans-dir
                     :in  (lerp 1.0 1.2 transit)
                     :out (lerp 1.2 1.0 transit)
                     1.0)]
    (if (<= back-alpha 0.001)
      []
      (persistent!
        (let [out (transient [])]
          ;; Node core rendering (depth writes at z=10, scale animation)
          (conj! out {:kind :enable-depth})
          (conj! out {:kind :push-pose})
          (conj! out {:kind :translate :x (+ x draw-align) :y (+ y draw-align) :z 10.0})
          (conj! out {:kind :scale :cx (/ total-size 2) :cy (/ total-size 2) :s node-scale})
          ;; 1. Draw skill_back without depth writes
          (conj! out {:kind :enable-blend})
          (conj! out {:kind :depth-mask :write? false})
          (conj! out {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a back-alpha})
          (conj! out {:kind :textured-quad :texture :skill-back :x 0 :y 0 :w (int total-size) :h (int total-size)})
          ;; 2. Dark gray outline back (for ALL nodes)
          (conj! out {:kind :alpha-color :r 0.2 :g 0.2 :b 0.2 :a (* back-alpha 0.6)})
          (conj! out {:kind :textured-quad :texture :skill-outline :x (int prog-align) :y (int prog-align) :w (int prog-size) :h (int prog-size)})
          (conj! out {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a 1.0})
          ;; 3. Depth mask from skill_back texture
          (conj! out {:kind :alpha-discard-depth-mask :texture :skill-back :alpha-threshold 0.3
                      :x 0 :y 0 :w (int total-size) :h (int total-size)})
          ;; 4. Depth mask from outline too (z+1 layer)
          (conj! out {:kind :push-pose})
          (conj! out {:kind :translate :x 0 :y 0 :z 1})
          (conj! out {:kind :alpha-discard-depth-mask :texture :skill-outline :alpha-threshold 0.5
                      :x (int prog-align) :y (int prog-align) :w (int prog-size) :h (int prog-size)})
          (conj! out {:kind :pop-pose})
          ;; 5. Disable depth writes
          (conj! out {:kind :depth-mask :write? false})
          ;; 6. Draw skill icon with alpha fade-in
          (conj! out {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a icon-alpha})
          (conj! out {:kind :depth-func :func :equal})
          (conj! out (if learned
                       {:kind :icon-or-fill :texture skill-icon :x (int align) :y (int align) :w (int icon-size) :h (int icon-size) :fallback-color 0xFF2A2A2A}
                       {:kind :shader-mono-blit :texture skill-icon :x (int align) :y (int align) :w (int icon-size) :h (int icon-size)}))
          (conj! out {:kind :depth-func :func :lequal})
          (conj! out {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a 1.0})
          ;; 7. Shader progress ring (learned only)
          (when learned (conj! out {:kind :disable-depth}))
          (when learned (conj! out {:kind :shader-progress-ring :shader-id :ring-progbar
                                    :texture-0 :skill-outline :texture-1 :skill-mask
                                    :progress (float (* progress-blend (or exp 0.0)))
                                    :x (int prog-align) :y (int prog-align) :w (int prog-size) :h (int prog-size)}))
          (when learned (conj! out {:kind :enable-depth}))
          (conj! out {:kind :pop-pose})
          ;; Connection lines (upstream: glDepthFunc GL_NOTEQUAL, glTranslated 0,0,11)
          (when (seq line-ops)
            (conj! out {:kind :depth-func :func :notequal})
            (conj! out {:kind :push-pose})
            (conj! out {:kind :translate :x 0 :y 0 :z 11})
            (doseq [op line-ops] (conj! out op))
            (conj! out {:kind :pop-pose})
            (conj! out {:kind :depth-func :func :lequal}))
          (conj! out {:kind :disable-depth})
          out)))))

;; ============================================================================
;; Detail popup — Common.initialize() equivalent for skill detail view
;; ============================================================================
(defn detail-popup-ops
  "Shared skill detail popup draw-ops. Corresponds to Common.initialize() in upstream SkillTree.scala.

   Without dev-state (terminal / read-only): shows static info, no LEARN button.
   With dev-state (developer context): shows energy cost, progress ring animation, LEARN button.

   node keys: :skill-name :skill-icon :skill-level :skill-description :learned :exp :skill-id
   dev-state keys: :is-developing? :progress :result (nil|:success|:failed) :error (nil|:low-energy|:low-level|:condition-fail)
   prerequisites: [{:icon-path str :accepted? bool}] — pre-computed from ability-data by caller"
  [node anim-time & [{:keys [dev-state est-consumption cx cy screen-w screen-h prerequisites]}]]
  (let [cx (or cx 210) cy (or cy 130)
        screen-w (int (or screen-w 420)) screen-h (int (or screen-h 260))
        back-sz 50 icon-sz 27 icon-ofs 11
        back-x (- cx 25) back-y (- cy 25)
        icon-x (+ back-x icon-ofs) icon-y (+ back-y icon-ofs)
        ta-y (+ cy 20)
        title-y (+ ta-y 3) info-y (+ ta-y 15)
        cond-y (+ ta-y 25) msg-y (+ ta-y 40)
        btn-x (- cx 16) btn-y (+ ta-y 52)
        {:keys [skill-name skill-icon skill-level skill-description learned exp]} node
        cover-alpha (* 0.7 (clamp01 (* anim-time 5.0)))
        panel-alpha (clamp01 (* (- anim-time 0.05) 5.0))
        is-developing? (boolean (:is-developing? dev-state))
        dev-progress (double (:progress dev-state 0.0))
        dev-result (:result dev-state)
        dev-error (:error dev-state)
        ring-progress (if is-developing? dev-progress
                          (if (= dev-result :success) 1.0 0.0))
        progress-at-1? (or (>= (or exp 0.0) 0.999) (= dev-result :success))
        show-learn-btn? (and (not is-developing?) (nil? dev-result))
        message (cond
                  is-developing?             (format (i18n/translate "skill_tree.my_mod.progress")
                                                     (int (* 100.0 dev-progress)))
                  (= dev-result :success)    (i18n/translate "skill_tree.my_mod.dev_successful")
                  (= dev-result :failed)     (i18n/translate "skill_tree.my_mod.dev_failed")
                  (= dev-error :low-energy)  (i18n/translate "skill_tree.my_mod.noenergy")
                  (= dev-error :low-level)   (format (i18n/translate "skill_tree.my_mod.level_fail")
                                                     (str skill-level))
                  (= dev-error :cond-fail)   (i18n/translate "skill_tree.my_mod.condition_fail")
                  dev-state                  (format (i18n/translate "skill_tree.my_mod.learn_question")
                                                     (format "%.0f" (double (or est-consumption 0))))
                  :else                      nil)
        cond-icon-sz 14
        cond-step 16
        cond-len (* cond-step (count prerequisites))
        cond-start-x (- (/ cond-len 2))]
    (persistent!
      (let [out (transient [{:kind :fill :x 0 :y 0 :w screen-w :h screen-h
                              :color (bit-or (bit-shift-left (int (* 255.0 cover-alpha)) 24) 0x000000)}])]
        (when (>= panel-alpha 0.01)
          (conj! out {:kind :textured-quad :texture :skill-back :x back-x :y back-y :w back-sz :h back-sz})
          (conj! out {:kind :icon-or-fill :texture skill-icon
                       :x icon-x :y icon-y :w icon-sz :h icon-sz :fallback-color 0xFF2A2A2A})
          (conj! out {:kind :shader-progress-ring :shader-id :ring-progbar
                       :texture-0 (if progress-at-1? :skill-view-outline-glow :skill-view-outline)
                       :texture-1 :skill-mask :progress (float ring-progress)
                       :x back-x :y back-y :w back-sz :h back-sz})
          (conj! out {:kind :text :x cx :y title-y :text (str skill-name " (LV " skill-level ")")
                       :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF})
          (if learned
            (do
              (conj! out {:kind :text :x cx :y info-y
                           :text (str (i18n/translate "skill_tree.my_mod.skill_exp") " "
                                      (int (* 100.0 (or exp 0.0))) "%")
                           :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff})
              (conj! out {:kind :text :x cx :y (+ ta-y 25) :text (str skill-description)
                           :font :ac-normal :font-size 9 :align :center :color 0xFFDDDDDD}))
            (do
              (conj! out {:kind :text :x cx :y info-y
                           :text (i18n/translate "skill_tree.my_mod.skill_not_learned")
                           :font :ac-normal :font-size 10 :align :center :color 0xFFff5555})
              (when (and dev-state show-learn-btn? (seq prerequisites))
                (doseq [idx (range (count prerequisites))]
                  (when-let [{:keys [icon-path accepted?]} (nth prerequisites idx nil)]
                    (when icon-path
                      (conj! out {:kind :icon-or-fill :texture icon-path
                                   :x (int (+ cx cond-start-x (* idx cond-step)))
                                   :y cond-y :w cond-icon-sz :h cond-icon-sz
                                   :fallback-color (if accepted? 0xFFFFFFFF 0xFF888888)})))))
              (when dev-state
                (conj! out {:kind :text :x cx :y (if (seq prerequisites) (+ cond-y cond-icon-sz 2) cond-y)
                             :text (str (i18n/translate "skill_tree.my_mod.req") " "
                                        (format "%.0f" (double (or est-consumption 0))))
                             :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF}))
              (when message
                (conj! out {:kind :text :x cx :y msg-y :text message
                             :font :ac-normal :font-size 10 :align :center :color 0xAAFFFFFF}))
              (when show-learn-btn?
                (conj! out {:kind :textured-quad :texture :tex-button :x btn-x :y btn-y :w 32 :h 16})
                (conj! out {:kind :text :x cx :y (+ btn-y 4) :text "LEARN"
                             :font :ac-bold :font-size 9 :align :center :color 0xFF101010})))))
        out))))

;; ============================================================================
;; Level-up popup — Common.initialize() equivalent for level-up view
;; ============================================================================
(defn level-up-popup-ops
  "Level-up confirmation/progress draw-ops. Corresponds to levelUpArea in upstream Common.initialize().

   target-level: int, the level the player is upgrading TO.
   condition-icon-path: string path for the level condition icon (abilities/condition/anyN.png).
   dev-state keys: :is-developing? :progress :result (nil|:success|:failed) :error (nil|:low-energy)
   cx, cy: center of overlay (developer panel: 200, 93; standalone: 210, 130)"
  [target-level condition-icon-path anim-time
   & [{:keys [dev-state est-consumption cx cy screen-w screen-h]}]]
  (let [cx (or cx 210) cy (or cy 130)
        screen-w (int (or screen-w 420)) screen-h (int (or screen-h 260))
        cover-alpha (* 0.7 (clamp01 (* anim-time 5.0)))
        panel-alpha (clamp01 (* (- anim-time 0.05) 5.0))
        icon-back-sz 50 icon-inner-sz 27
        icon-ofs (/ (- icon-back-sz icon-inner-sz) 2)
        icon-x (- cx 25) icon-y (- cy 25)
        icon-inner-x (+ icon-x icon-ofs) icon-inner-y (+ icon-y icon-ofs)
        text-base-y (+ cy 25)
        btn-sz 32
        btn-x (int (- cx (/ btn-sz 2))) btn-y (+ text-base-y 40)
        is-developing? (boolean (:is-developing? dev-state))
        dev-progress (double (:progress dev-state 0.0))
        dev-result (:result dev-state)
        ring-progress (if is-developing? dev-progress
                          (if (= dev-result :success) 1.0 0.0))
        progress-at-1? (= dev-result :success)
        show-learn-btn? (and (not is-developing?) (nil? dev-result))
        lvltext (format (i18n/translate "skill_tree.my_mod.uplevel") (str "Lv." target-level))
        reqtext (str (i18n/translate "skill_tree.my_mod.req") " "
                     (format "%.0f" (double (or est-consumption 0))))
        hint (cond
               is-developing?             (i18n/translate "skill_tree.my_mod.dev_developing")
               (= dev-result :success)    (i18n/translate "skill_tree.my_mod.dev_successful")
               (= dev-result :failed)     (i18n/translate "skill_tree.my_mod.dev_failed")
               (= (:error dev-state) :low-energy) (i18n/translate "skill_tree.my_mod.noenergy")
               dev-state                  (i18n/translate "skill_tree.my_mod.level_question")
               :else                      nil)]
    (persistent!
      (let [out (transient [{:kind :fill :x 0 :y 0 :w screen-w :h screen-h
                              :color (bit-or (bit-shift-left (int (* 255.0 cover-alpha)) 24) 0x000000)}])]
        (when (>= panel-alpha 0.01)
          (conj! out {:kind :textured-quad :texture :skill-back :x icon-x :y icon-y :w icon-back-sz :h icon-back-sz})
          (conj! out {:kind :icon-or-fill :texture condition-icon-path
                       :x (int icon-inner-x) :y (int icon-inner-y) :w icon-inner-sz :h icon-inner-sz
                       :fallback-color 0xFF2A2A2A})
          (conj! out {:kind :shader-progress-ring :shader-id :ring-progbar
                       :texture-0 (if progress-at-1? :skill-view-outline-glow :skill-view-outline)
                       :texture-1 :skill-mask :progress (float ring-progress)
                       :x icon-x :y icon-y :w icon-back-sz :h icon-back-sz})
          (conj! out {:kind :text :x cx :y (+ text-base-y 3) :text lvltext
                       :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF})
          (conj! out {:kind :text :x cx :y (+ text-base-y 16) :text reqtext
                       :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF})
          (when hint
            (conj! out {:kind :text :x cx :y (+ text-base-y 26) :text hint
                         :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF}))
          (when show-learn-btn?
            (conj! out {:kind :textured-quad :texture :tex-button :x btn-x :y btn-y :w btn-sz :h 16})
            (conj! out {:kind :text :x cx :y (+ btn-y 4) :text "LEARN"
                         :font :ac-bold :font-size 9 :align :center :color 0xFF101010})))
        out))))

;; ============================================================================
;; Main draw ops builder
;; ============================================================================
(defn build-tree-ops
  "Shared skill tree draw ops: nodes + connections + hover tooltip + detail popup.
   Used by BOTH full-screen path (host.clj) and developer panel (panel.clj).
   Transient building — no mapcat/concat overhead.
   When :static? is true, skips parallax offset (for cached static layer)."
  [rd anim mx01 my01 hid htrans sel-id & {:keys [static?]}]
  (let [;; Parallax offsets for skill nodes (upstream: clampf(0,1,mouseX/width) - 0.5) * max_du_skills
        node-dx (if static? 0.0 (* (- mx01 0.5) 10.0))
        node-dy (if static? 0.0 (* (- my01 0.5) 10.0))
        ;; Animated connection lines — grouped by child node's index
        raw-conns (or (:connections rd) [])
        anim-conns (mapv (fn [c]
                           (let [child-idx (or (:child-idx c) 0)
                                 dt (max 0.0 (- anim (* child-idx 0.08) 0.1))
                                 lb (clamp01 (* dt 5.0))]
                             (assoc c
                               :from-x (- (:from-x c) node-dx)
                               :from-y (- (:from-y c) node-dy)
                               :to-x   (- (:to-x c) node-dx)
                               :to-y   (- (:to-y c) node-dy)
                               :lb     lb)))
                         raw-conns)
        conns-by-idx (group-by #(get % :child-idx) anim-conns)
        raw-nodes (or (:skill-nodes rd) [])
        shifted-nodes (mapv #(assoc % :x (- (:x %) node-dx) :y (- (:y %) node-dy)) raw-nodes)
        ;; Hover tooltip
        hover-id (:hover-skill rd)
        hover-node (when hover-id (first (filter #(= (:skill-id %) hover-id) (:skill-nodes rd))))
        ;; Detail popup
        sel-node (when sel-id (first (filter #(= (:skill-id %) sel-id) (:skill-nodes rd))))]
    (persistent!
      (let [out (transient [])]
        ;; Skill nodes + connections
        (doseq [[idx n] (map-indexed vector shifted-nodes)]
          (let [ops (node-ops n anim hid htrans (build-line-ops (get conns-by-idx idx) anim))]
            (doseq [op ops] (conj! out op))))
        ;; Hover tooltip
        (when hover-node
          (conj! out {:kind :fill :x 230 :y 8 :w 180 :h 68 :color 0xC0202020})
          (conj! out {:kind :text :x 236 :y 14 :text (str (:skill-name hover-node)) :font :ac-bold :font-size 12 :align :left :color 0xFFFFFFFF})
          (conj! out {:kind :text :x 236 :y 28 :text (str (:skill-description hover-node)) :font :ac-normal :font-size 9 :align :left :color 0xFFDDDDDD})
          (conj! out {:kind :text :x 236 :y 42 :text (format "Progress: %d%%" (int (* 100.0 (:exp hover-node)))) :font :ac-normal :font-size 8 :align :left :color 0xFFDDDDDD})
          (conj! out {:kind :text :x 236 :y 56 :text (if (:learned hover-node) "Learned" "Not learned") :font :ac-normal :font-size 8 :align :left
                      :color (if (:learned hover-node) 0xFF88FF88 0xFFFF8888)}))
        ;; Detail popup
        (when sel-node
          (let [ops (detail-popup-ops sel-node anim)]
            (doseq [op ops] (conj! out op))))
        out))))

(defn build-draw-ops
  "Full-screen skill tree draw ops. Thin wrapper that adds background + header + level-up.
  Transient building — no concat/vec overhead.
  When :static? is true, skips mouse-driven parallax (for cached layer)."
  ([owner mx my screen-w screen-h] (build-draw-ops owner mx my screen-w screen-h nil))
  ([owner mx my screen-w screen-h {:keys [static?]}]
  (if-let [rd (build-screen-render-data owner)]
    (let [st (screen-state-snapshot owner)
          ct (:creation-time st)
          anim (if ct (/ (- (now-ms) ct) 1000.0) 5.0)
          ab (:ability-info rd)
          mx01 (clamp01 (/ (double mx) (max 1.0 (double (or screen-w 420)))))
          my01 (clamp01 (/ (double my) (max 1.0 (double (or screen-h 260)))))
          bg-dx (* (- mx01 0.5) 0.01)
          bg-dy (* (- my01 0.5) 0.01)
          bg-scale-fn (fn [x] (+ (* (- x 0.5) back-scale-inv) 0.5))
          bg-u (bg-scale-fn bg-dx) bg-v (bg-scale-fn bg-dy)]
      (persistent!
        (let [out (transient [])]
          ;; Background UV
          (conj! out {:kind :raw-rect-uv :texture :bg-area :x 0 :y 0 :w (int 420) :h (int 260)
                      :min-u (float bg-u) :max-u (float (+ bg-u back-scale-inv))
                      :min-v (float bg-v) :max-v (float (+ bg-v back-scale-inv))})
          ;; Header overlay + text
          (conj! out {:kind :fill :x 0 :y 0 :w 420 :h 260 :color 0xA0101010})
          (conj! out {:kind :text :x 12 :y 8  :text (str "Category: " (:category-name ab)) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF})
          (conj! out {:kind :text :x 12 :y 22 :text (format "Level: %d" (int (or (:level ab) 0))) :font :ac-normal :font-size 9 :align :left :color 0xFFE8E8E8})
          (conj! out {:kind :text :x 12 :y 36 :text (format "CP: %.0f / %.0f" (double (get-in ab [:cp :cur] 0.0)) (double (get-in ab [:cp :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFAED7FF})
          (conj! out {:kind :text :x 12 :y 50 :text (format "Overload: %.0f / %.0f" (double (get-in ab [:overload :cur] 0.0)) (double (get-in ab [:overload :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFFFB8A6})
          ;; Level-up button
          (when (and (:can-level-up ab) (not (:showing-level-up-popup? st)))
            (conj! out {:kind :fill :x 10 :y 200 :w 80 :h 20 :color 0xAA22AA22})
            (conj! out {:kind :text :x 18 :y 206 :text "Level Up" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF}))
          ;; Skill tree nodes
          (let [tree (build-tree-ops rd anim mx01 my01
                       (:hovered-skill-id st) (:hover-node-transitions st {})
                       (:selected-skill st) :static? static?)]
            (doseq [op tree] (conj! out op)))
          ;; Level-up popup
          (when (:showing-level-up-popup? st)
            (let [open-ms (:level-up-popup-open-ms st 0)
                  popup-anim (/ (- (now-ms) open-ms) 1000.0)
                  current-level (int (or (:level ab) 1))
                  target-level (inc current-level)
                  cond-icon (modid/asset-path "textures"
                              (str "abilities/condition/any" target-level ".png"))
                  popup-ops (level-up-popup-ops target-level cond-icon popup-anim
                              {:dev-state (:level-up-dev-state st)
                               :screen-w 420 :screen-h 260})]
              (doseq [op popup-ops] (conj! out op))))
          out)))
    [])))
