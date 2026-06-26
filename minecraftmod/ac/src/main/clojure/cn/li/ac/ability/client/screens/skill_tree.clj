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
   [cn.li.mcmod.i18n :as i18n]
   [cn.li.mcmod.util.log :as log]))

(defn- now-ms [] (System/currentTimeMillis))
(defn- clamp01 [v] (max 0.0 (min 1.0 (double v))))
(defn- lerp [a b t] (+ a (* (- b a) (clamp01 t))))

;; ============================================================================
;; Constants (matching upstream SkillTree.scala)
;; ============================================================================
(def ^:private max-progress-segments 24)
(def ^:private widget-size 16.0)
(def ^:private total-size 23.0)
(def ^:private prog-size 31.0)
(def ^:private icon-size 14.0)
(def ^:private draw-align (/ (- widget-size total-size) 2))   ;; -3.5
(def ^:private prog-align (/ (- total-size prog-size) 2))      ;; -4.0
(def ^:private align (/ (- total-size icon-size) 2))           ;; 4.5
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
   :creation-time nil :mouse-x 0 :mouse-y 0 :hovered-skill-id nil :hover-start-time 0})

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
  (let [ordered (vec (sort-by (juxt :level :id) skills))
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
     :progress-segments (int (Math/round (* prog max-progress-segments)))}))

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
                   (filter :enabled (skill/get-skills-for-category cid)))
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

(defn on-level-up-click [owner] (api/req-level-up! owner))

(defn handle-screen-click! [owner mx my]
  (let [st (screen-state-snapshot owner) sel (:selected-skill st)]
    (if sel
      ;; Detail popup shown: cx=210, cy=130, btn at (194,202) size 32×16
      (let [cx 210 cy 130
            btn-x (- cx 16) btn-y (+ cy 72)   ; 194, 202
            btn-x2 (+ btn-x 32) btn-y2 (+ btn-y 16)
            sel-node (when-let [rd (build-screen-render-data owner)]
                       (first (filter #(= (:skill-id %) sel) (:skill-nodes rd))))]
        (cond
          ;; LEARN button for unlearned skill
          (and sel-node (not (:learned sel-node))
               (>= mx btn-x) (<= mx btn-x2) (>= my btn-y) (<= my btn-y2))
          (do (on-skill-learn-click owner sel) true)
          ;; Click anywhere else dismisses popup
          :else (do (swap-screen-state! owner assoc :selected-skill nil) true)))
      (if-let [rd (build-screen-render-data owner)]
        (let [c? (atom false)]
          (doseq [n (:skill-nodes rd)] (when (and n (not @c?))
            (when (< (+ (* (- mx (:x n)) (- mx (:x n))) (* (- my (:y n)) (- my (:y n)))) 400)
              (on-skill-click owner (:skill-id n)) (reset! c? true))))
          (when (and (not @c?) (get-in rd [:ability-info :can-level-up])
                     (>= mx 10) (<= mx 90) (>= my 200) (<= my 220))
            (on-level-up-click owner) (reset! c? true))
          (when (and (not @c?) (:selected-skill (screen-state-snapshot owner)))
            (swap-screen-state! owner assoc :selected-skill nil))
          (boolean @c?))
        false))))

(defn on-mouse-move [owner mx my]
  (let [rd (build-screen-render-data owner) nodes (:skill-nodes rd)
        h (when nodes (first (filter #(< (+ (* (- mx (:x %)) (- mx (:x %))) (* (- my (:y %)) (- my (:y %)))) 400) nodes)))
        prev (:hovered-skill-id (screen-state-snapshot owner))]
    (swap-screen-state! owner
      (fn [st] (-> st (assoc :mouse-x mx :mouse-y my :hover-skill (:skill-id h) :hovered-skill-id (:skill-id h))
                   (cond-> (not= (:skill-id h) prev) (assoc :hover-start-time (now-ms))))))))

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
;; Draw Ops — Line connections (rotated-quad)
;; ============================================================================
(defn- build-line-ops [connections anim-time]
  (mapcat (fn [{:keys [from-x from-y to-x to-y child-learned? m-alpha child-idx]}]
            (let [line-alpha (* (or m-alpha 0.7) (if child-learned? 1.0 0.4))
                  alpha-byte (int (* 255.0 (clamp01 line-alpha)))
                  color (bit-or (bit-shift-left alpha-byte 24) 0xFFFFFF)
                  dx (- to-x from-x) dy (- to-y from-y)
                  norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
              (when (pos? norm)
                (let [ndx (/ dx norm) ndy (/ dy norm)
                      x0 (+ from-x (* ndx 12.2)) y0 (+ from-y (* ndy 12.2))
                      x1 (- to-x (* ndx 12.2)) y1 (- to-y (* ndy 12.2))]
                  [{:kind :rotated-quad :x0 x0 :y0 y0 :x1 x1 :y1 y1
                    :line-width 5.5 :color color}]))))
          connections))

;; ============================================================================
;; Draw Ops — Skill nodes (textured, depth-layered, animated)
;; ============================================================================
(defn- node-ops [node anim-time hovered-id hover-start]
  (let [{:keys [x y idx learned can-learn locked? skill-name skill-icon
                progress-segments exp m-alpha skill-id]} node
        effective-m-alpha (or m-alpha 0.7)
        effective-can-learn (and can-learn (not locked?))
        effective-learned (and learned (not locked?))
        dt (max 0.0 (- anim-time (* idx 0.08) 0.1))
        back-alpha (* effective-m-alpha (clamp01 (* dt 10.0)))
        icon-alpha (* effective-m-alpha (clamp01 (* (- dt 0.08) 10.0)))
        progress-blend (clamp01 (* (- dt 0.12) 2.0))
        hover-now (= skill-id hovered-id)
        hover-elapsed (if hover-start (/ (- (now-ms) hover-start) 1000.0) 0.0)
        transit (clamp01 (/ hover-elapsed 0.1))
        node-scale (if hover-now (lerp 1.0 1.2 transit) (lerp 1.2 1.0 transit))]
    (if (<= back-alpha 0.001) []
      [{:kind :push-pose}
       {:kind :translate :x (+ x draw-align) :y (+ y draw-align) :z 10.0}
       {:kind :scale :cx (/ total-size 2) :cy (/ total-size 2) :s node-scale}
       {:kind :enable-blend}
       {:kind :depth-mask :write? false}
       {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a back-alpha}
       {:kind :textured-quad :texture :skill-back :x 0 :y 0 :w (int total-size) :h (int total-size)}
       {:kind :alpha-color :r 0.2 :g 0.2 :b 0.2 :a (* back-alpha 0.6)}
       {:kind :textured-quad :texture :skill-outline :x (int prog-align) :y (int prog-align) :w (int prog-size) :h (int prog-size)}
       {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a 1.0}
       {:kind :depth-mask :write? true}
       {:kind :color-mask :write? false}
       {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a icon-alpha}
       {:kind :color-mask :write? true}
       (if learned
         {:kind :icon-or-fill :texture skill-icon :x (int align) :y (int align) :w (int icon-size) :h (int icon-size) :fallback-color 0xFF2A2A2A}
         {:kind :shader-progress-ring :shader-id :mono :texture-0 skill-icon :progress 0.0
          :x (int align) :y (int align) :w (int icon-size) :h (int icon-size)})
       {:kind :alpha-color :r 1.0 :g 1.0 :b 1.0 :a 1.0}
       (when learned
         {:kind :shader-progress-ring :shader-id :skill-progbar
          :texture-0 :skill-outline :texture-1 :skill-mask
          :progress (float (* progress-blend exp))
          :x (int prog-align) :y (int prog-align) :w (int prog-size) :h (int prog-size)})
       {:kind :depth-mask :write? false}
       {:kind :disable-depth}
       {:kind :pop-pose}
       {:kind :text :x (+ x 24) :y (+ y 6) :text (str skill-name)
        :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF}])))

;; ============================================================================
;; Detail popup
;; ============================================================================
(defn- detail-popup-ops [node anim-time]
  ;; Screen 420×260 → cx=210, cy=130
  ;; Original skillViewArea: skillWid.centered().size(50,50), textArea.centered().pos(0,25)
  (let [cx 210 cy 130
        back-sz 50 icon-sz 27 icon-ofs 11
        back-x (- cx 25) back-y (- cy 25)                       ; 185, 105
        icon-x (+ back-x icon-ofs) icon-y (+ back-y icon-ofs)   ; 196, 116
        ta-y (+ cy 20)       ; textArea top = cy-5+25 = 150
        title-y (+ ta-y 3)   ; 153
        info-y  (+ ta-y 15)  ; 165
        learnq-y (+ ta-y 40) ; 190
        btn-x (- cx 16) btn-y (+ ta-y 52)                       ; 194, 202
        {:keys [skill-name skill-icon skill-level skill-description learned exp]} node
        cover-alpha (* 0.7 (clamp01 (* anim-time 5.0)))
        panel-alpha (clamp01 (* (- anim-time 0.05) 5.0))
        progress-at-1? (>= (or exp 0.0) 0.999)]
    (filterv some?
      (concat
        ;; Dark full-screen cover (original: blackCover)
        [{:kind :fill :x 0 :y 0 :w 420 :h 260
          :color (bit-or (bit-shift-left (int (* 255.0 cover-alpha)) 24) 0x000000)}]
        (when (>= panel-alpha 0.01)
          [;; skill_back 50×50 centered (texSkillBack in drawActionIcon)
           {:kind :textured-quad :texture :skill-back :x back-x :y back-y :w back-sz :h back-sz}
           ;; Skill icon 27×27 at offset 11 (original IconAlign≈11.5)
           {:kind :icon-or-fill :texture skill-icon
            :x icon-x :y icon-y :w icon-sz :h icon-sz :fallback-color 0xFF2A2A2A}
           ;; Shader ring — progress=0 for popup (original drawActionIcon progress=0)
           {:kind :shader-progress-ring :shader-id :skill-progbar
            :texture-0 (if progress-at-1? :skill-view-outline-glow :skill-view-outline)
            :texture-1 :skill-mask :progress (float 0.0)
            :x back-x :y back-y :w back-sz :h back-sz}
           ;; Title centered at cx
           {:kind :text :x cx :y title-y :text (str skill-name " (LV " skill-level ")")
            :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF}
           (if learned
             ;; Learned: EXP + description
             [{:kind :text :x cx :y info-y
               :text (format "EXP: %.0f%%" (* 100.0 (or exp 0.0)))
               :font :ac-normal :font-size 9 :align :center :color 0xFFa1e1ff}
              {:kind :text :x cx :y (+ ta-y 25) :text (str skill-description)
               :font :ac-normal :font-size 9 :align :center :color 0xFFDDDDDD}]
             ;; Unlearned: Not learned + LEARN button (button.png 32×16)
             [{:kind :text :x cx :y info-y :text "Not learned"
               :font :ac-normal :font-size 10 :align :center :color 0xFFff5555}
              {:kind :text :x cx :y learnq-y :text "Learn?"
               :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF}
              {:kind :textured-quad :texture :tex-button :x btn-x :y btn-y :w 32 :h 16}
              {:kind :text :x cx :y (+ btn-y 4) :text "LEARN"
               :font :ac-bold :font-size 9 :align :center :color 0xFF101010}])])))))

;; ============================================================================
;; Main draw ops builder
;; ============================================================================
(defn build-draw-ops [owner _mx _my]
  (if-let [rd (build-screen-render-data owner)]
    (let [st (screen-state-snapshot owner)
          ct (:creation-time st)
          anim (if ct (/ (- (now-ms) ct) 1000.0) 5.0)
          ab (:ability-info rd)

          ;; Parallax background — matching upstream: scale(dx*max_du), scale(dy*max_du)
          mx (clamp01 (/ (:mouse-x st 0) (max 1.0 (double 420))))
          my (clamp01 (/ (:mouse-y st 0) (max 1.0 (double 260))))
          bg-dx (* (- mx 0.5) 0.01)
          bg-dy (* (- my 0.5) 0.01)
          bg-scale-fn (fn [x] (+ (* (- x 0.5) back-scale-inv) 0.5))
          bg-u (bg-scale-fn bg-dx)
          bg-v (bg-scale-fn bg-dy)
          bg-ops [{:kind :raw-rect-uv :texture :bg-area :x 0 :y 0 :w (int 420) :h (int 260)
                   :min-u (float bg-u) :max-u (float (+ bg-u back-scale-inv))
                   :min-v (float bg-v) :max-v (float (+ bg-v back-scale-inv))}]

          header [{:kind :fill :x 0 :y 0 :w 420 :h 260 :color 0xA0101010}
                  {:kind :text :x 12 :y 8  :text (str "Category: " (:category-name ab)) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF}
                  {:kind :text :x 12 :y 22 :text (format "Level: %d" (int (or (:level ab) 0))) :font :ac-normal :font-size 9 :align :left :color 0xFFE8E8E8}
                  {:kind :text :x 12 :y 36 :text (format "CP: %.0f / %.0f" (double (get-in ab [:cp :cur] 0.0)) (double (get-in ab [:cp :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFAED7FF}
                  {:kind :text :x 12 :y 50 :text (format "Overload: %.0f / %.0f" (double (get-in ab [:overload :cur] 0.0)) (double (get-in ab [:overload :max] 0.0))) :font :ac-normal :font-size 9 :align :left :color 0xFFFFB8A6}]

          level-up (when (:can-level-up ab)
                     [{:kind :fill :x 10 :y 200 :w 80 :h 20 :color 0xAA22AA22}
                      {:kind :text :x 18 :y 206 :text "Level Up" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF}])

          ;; Parallax offsets for skill nodes (upstream: max_du_skills = 10)
          node-dx (* (- (/ (:mouse-x st 0) (max 1.0 (double 420))) 0.5) 10.0)
          node-dy (* (- (/ (:mouse-y st 0) (max 1.0 (double 260))) 0.5) 10.0)

          ;; Animated connection lines — per-node stagger and parallax applied to endpoints
          raw-conns (or (:connections rd) [])
          anim-conns (mapv (fn [c]
                             (let [child-idx (or (:child-idx c) 0)
                                   dt (max 0.0 (- anim (* child-idx 0.08) 0.1))
                                   lb (clamp01 (* dt 5.0))]
                               (assoc c
                                 :from-x (- (:from-x c) node-dx)
                                 :from-y (- (:from-y c) node-dy)
                                 :to-x   (- (lerp (:from-x c) (:to-x c) lb) node-dx)
                                 :to-y   (- (lerp (:from-y c) (:to-y c) lb) node-dy))))
                           raw-conns)
          connection-ops (build-line-ops anim-conns anim)
          hid (:hovered-skill-id st)
          hst (:hover-start-time st)
          raw-nodes (or (:skill-nodes rd) [])
          shifted-nodes (mapv #(assoc % :x (- (:x %) node-dx) :y (- (:y %) node-dy)) raw-nodes)
          nodes (mapcat #(node-ops % anim hid hst) shifted-nodes)

          hover-id (:hover-skill rd)
          hover-node (when hover-id (first (filter #(= (:skill-id %) hover-id) (:skill-nodes rd))))
          tooltip (when hover-node
                    [{:kind :fill :x 230 :y 8 :w 180 :h 68 :color 0xC0202020}
                     {:kind :text :x 236 :y 14 :text (str (:skill-name hover-node)) :font :ac-bold :font-size 12 :align :left :color 0xFFFFFFFF}
                     {:kind :text :x 236 :y 28 :text (str (:skill-description hover-node)) :font :ac-normal :font-size 9 :align :left :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 42 :text (format "Progress: %d%%" (int (* 100.0 (:exp hover-node)))) :font :ac-normal :font-size 8 :align :left :color 0xFFDDDDDD}
                     {:kind :text :x 236 :y 56 :text (if (:learned hover-node) "Learned" "Not learned") :font :ac-normal :font-size 8 :align :left
                      :color (if (:learned hover-node) 0xFF88FF88 0xFFFF8888)}])

          sel-id (:selected-skill st)
          sel-node (when sel-id (first (filter #(= (:skill-id %) sel-id) (:skill-nodes rd))))
          detail-ops (when sel-node (detail-popup-ops sel-node anim))]

      (vec (concat bg-ops header level-up connection-ops nodes tooltip detail-ops)))
    []))
