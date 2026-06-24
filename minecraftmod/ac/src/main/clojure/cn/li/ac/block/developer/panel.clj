(ns cn.li.ac.block.developer.panel
  "Classic AcademyCraft `page_developer.xml` bindings + right-panel mode dispatch.

  Right panel (`parent_right/area`) dynamically switches between:
  - :skill-tree   — player has category → render skill nodes + click-to-learn
  - :console      — player has no category → text console with 'learn' command
  - :reset-console — player holds magnetic coil → reset console

  Left panel bindings remain as before. btn_upgrade → overlay (not separate screen).
  Wireless button → overlay popup (not tab switch).
  Sync rate bar uses fixed device property from developer-specs."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.ability.registry.category :as acat]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree]
            [cn.li.ac.block.developer.console :as dev-console]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.mcmod.platform.be :as platform-be]))

;; ============================================================================
;; Helpers — widget manipulation
;; ============================================================================

(defn- textbox-of [widget]
  (comp/get-widget-component widget :textbox))

(defn- set-text-path! [root path text]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [tb (textbox-of w)]
      (comp/set-text! tb (str text)))))

(defn- set-progress-path! [root path ^double v]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [pb (comp/get-widget-component w :progressbar)]
      (comp/set-progress! pb v))))

(def ^:private max-console-lines 50)

(defn- clamp-lines
  "Keep at most `max-console-lines` entries, dropping oldest first."
  [lines]
  (let [cnt (count lines)]
    (if (> cnt max-console-lines)
      (subvec (vec lines) (- cnt max-console-lines))
      (vec lines))))

(defn- set-drawtexture-path! [root path texture-path]
  (when (string? texture-path)
    (when-let [w (cgui-core/find-widget root path)]
      (when-let [dt (comp/get-drawtexture-component w)]
        (comp/set-texture! dt texture-path)))))

(defn- set-visible-path! [root path visible?]
  (when-let [w (cgui-core/find-widget root path)]
    (cgui-core/set-visible! w visible?)))

;; ============================================================================
;; Left panel — ability info and machine status (mostly unchanged)
;; ============================================================================

(defn- dev-msg [action]
  (msg-registry/msg :developer action))

(defn- req-start-development!
  "Send a development request. If container has :on-dev-start callback,
  delegates to it (for portable/instant dev). Otherwise sends network
  message for timed block-based development."
  [container action & [extra callback]]
  (if-let [handler (:on-dev-start container)]
    ;; Portable/instant dev path — delegate to container's handler
    (handler action extra callback)
    ;; Block dev path — send network message for timed session
    (let [owner (try (container-state/owner-from-container container)
                     (catch Exception e
                       (log/error "[req-start-development!] owner error:" (ex-message e))
                       nil))
          msg-id (dev-msg :start-development)
          payload (action-payload/action-payload container (merge {:action action} extra))]
      (log/info "[req-start-development!] sending" msg-id "action=" action
                "owner=" (pr-str owner) "payload=" (pr-str payload))
      (net-client/send-to-server
        owner
        msg-id
        payload
        (fn [resp]
          (log/info "[req-start-development!] response:" (pr-str resp))
          (when callback (callback resp)))))))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      (modid/asset-path "textures" icon-str))))

(defn- default-ability-icon-path []
  (modid/asset-path "textures" "guis/icons/icon_nocategory.png"))

(defn- normalize-tier [tier]
  (let [k (keyword (or tier :normal))]
    (if (developer/developer-type? k) k :normal)))

(defn- current-developer-type [container]
  (let [tile (:tile-entity container)
        block-tier (when tile
                     (some-> (platform-be/get-block-id tile)
                             developer/developer-type-for-block-id))
        state-tier (some-> (:tier container) deref normalize-tier)]
    (or block-tier state-tier :normal)))

(defn- category-ui-model
  [{:keys [ad cat dev? developer-type energy max-energy bandwidth]}]
  (let [cat-id (:category-id ad)
        has-category? (boolean cat)
        lvl (if has-category? (long (or (:level ad) 1)) 0)
        level-prog (double (:level-progress ad 0.0))
        skills-at-level (when cat-id
                          (skill-query/get-controllable-skills-at-level cat-id lvl))
        cat-rate (when cat-id (acat/get-prog-incr-rate cat-id))
        thresh (when (and cat-id (not (>= lvl 5)))
                 (learning-rules/level-up-threshold ad
                   skills-at-level cat-rate (cfg/prog-incr-rate)))
        cat-prog01 (if has-category?
                     (if (and thresh (pos? thresh))
                       (max 0.02 (bal/clamp01 (/ level-prog thresh)))
                       (if (>= lvl 5) 1.0 0.02))
                     0.0)
        can-upgrade? (and has-category? (< lvl 5)
                          (developer/gte? developer-type (developer/min-for-level (inc lvl)))
                          (if thresh (>= level-prog thresh) true))
        ability-name (if has-category? (i18n/translate (:name-key cat)) "N/A")
        icon-path (if has-category?
                    (or (some-> cat :icon texture-path-from-category-icon)
                        (default-ability-icon-path))
                    (default-ability-icon-path))
        exp-label (if has-category?
                    (if (>= lvl 5) "MAX"
                        (if thresh (format "EXP %.0f%%" (* 100.0 cat-prog01)) "EXP 0%"))
                    "EXP 0%")
        level-label (cond dev? "Learning"
                          :else (format "Lv.%d" lvl))]  ;; matching original levelDesc
    {:has-category? has-category?
     :can-upgrade? can-upgrade?
     :ability-name ability-name
     :icon-path icon-path
     :exp-label exp-label
     :level-label level-label
     :cat-prog01 cat-prog01
     :sync-rate (:bandwidth (developer/developer-spec developer-type) 0.7)  ;; fixed device property
     :power01 (bal/clamp01 (/ energy max-energy))}))

(declare current-ui-model-in-session)
(declare render-skill-tree-area!)

(defn- current-ui-model [container player]
  (current-ui-model-in-session
    (runtime-hooks/require-player-state-session-id "developer.panel")
    container player))

(defn- current-ui-model-in-session
  [session-id container player]
  (let [energy (double (or @(:energy container) 0.0))
        max-energy (max 1.0 (double (or @(:max-energy container) 1.0)))
        dev? (boolean (or @(:is-developing container) false))
        uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state* session-id uuid-str))
        ad (:ability-data pstate)
        cat-id (:category-id ad)
        cat (when cat-id (acat/get-category cat-id))
        developer-type (current-developer-type container)]
    (category-ui-model {:ad ad :cat cat :dev? dev? :developer-type developer-type
                        :energy energy :max-energy max-energy
                        :bandwidth (:bandwidth (developer/developer-spec developer-type) 0.7)})))

;; ============================================================================
;; Overlay helpers
;; ============================================================================

(def ^:private cover-fade-duration 0.2)
(def ^:private cover-max-alpha 0.7)

(defn- time-secs [] (/ (double (System/currentTimeMillis)) 1000.0))

(defn- create-fading-cover
  "Create a fading black overlay matching original Cover component.
  Returns {:cover <widget> :end-cover! <fn>}."
  [parent root]
  (let [[pw ph] (cgui-core/get-size parent)
        cover (cgui-core/create-widget :pos [0 0] :size [pw ph])
        _ (comp/add-component! cover (comp/draw-texture nil (comp/mono-blend 0.0 0.0)))
        dt-comp (first (filter #(= (or (:kind %) (::kind %)) :drawtexture) @(:components cover)))
        state (atom {:start-time (time-secs) :ended? false :end-start-time 0.0})]
    (events/on-frame cover
      (fn [_]
        (let [{:keys [start-time ended? end-start-time]} @state
              t (time-secs) elapsed (- t (if ended? end-start-time start-time))
              src (min 1.0 (/ (max 0.0 elapsed) cover-fade-duration))
              alpha (if ended? (* cover-max-alpha (- 1.0 src)) (* cover-max-alpha src))]
          (when dt-comp
            (swap! (:state dt-comp) assoc :color (comp/mono-blend 0.0 (max 0.0 alpha))))
          (let [[rw rh] (cgui-core/get-size root)] (cgui-core/set-size! cover rw rh))
          (when (and ended? (<= alpha 0.0)) (cgui-core/remove-widget! root cover)))))
    (let [end-fn (fn [] (when-not (:ended? @state) (swap! state assoc :ended? true :end-start-time (time-secs))))]
      {:cover cover :end-cover! end-fn})))

(defn- register-overlay! [root end-fn]
  (swap! (:metadata root) update :cover-end-fns (fn [s] (conj (or s []) end-fn))))

(defn- unregister-overlay! [root end-fn]
  (swap! (:metadata root) update :cover-end-fns (fn [s] (vec (remove #{end-fn} (or s []))))))

(defn- create-centered-panel
  "Create a centered panel widget inside cover for overlay content."
  [cover panel-width panel-height]
  (let [[cw ch] (cgui-core/get-size cover)
        cx (int (/ (- cw panel-width) 2))
        cy (int (/ (- ch panel-height) 2))
        panel (cgui-core/create-widget :pos [cx cy] :size [panel-width panel-height])]
    (cgui-core/add-widget! cover panel)
    panel))

;; ============================================================================
;; Skill detail overlay
;; ============================================================================

(defn- create-skill-detail-overlay!
  "Create overlay showing skill details with Cover fade, ESC stack, dev state."
  [root container skill-id _developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn [should-rebuild?]
                   (unregister-overlay! root end-cover!)
                   (end-cover!))
        _ (register-overlay! root end-cover!)
        dev-type (or _developer-type :normal)
        dev-spec (developer/developer-spec dev-type)
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")
        skill-level (or (:level skill-spec) 1)
        est-consumption (long (* (:cps dev-spec 700.0)
                                (+ 3 (* skill-level skill-level 0.5))))
        panel (create-centered-panel cover 200 155)
        bg (cgui-core/create-widget :pos [0 0] :size [200 155])
        should-rebuild (atom false)
        can-close? (atom true)]
    ;; Close outside
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              [px py] (cgui-core/get-pos panel) [pw ph] (cgui-core/get-size panel)]
          (when (and @can-close? (or (< mx px) (> mx (+ px pw)) (< my py) (> my (+ py ph))))
            (close-fn @should-rebuild)))))
    (comp/add-component! bg (comp/draw-texture nil 0xC0202020))
    (cgui-core/add-widget! panel bg)
    ;; Title
    (let [title (cgui-core/create-widget :pos [10 8] :size [180 14])
          tb (comp/text-box :text (str skill-name " (LV " skill-level ")") :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb) (cgui-core/add-widget! panel title))
    ;; Energy estimate
    (let [energy-w (cgui-core/create-widget :pos [10 26] :size [180 12])
          energy-tb (comp/text-box :text (str "Req: " est-consumption " IF") :font :ac-normal :font-size 8 :align :center :color 0xAAFFFFFF)]
      (comp/add-component! energy-w energy-tb) (cgui-core/add-widget! panel energy-w))
    ;; Skill icon
    (let [skill-icon-path (skill-query/get-skill-icon-path skill-id)]
      (when skill-icon-path
        (let [icon-w (cgui-core/create-widget :pos [80 42] :size [24 24])
              icon-path (modid/asset-path "textures" (subs skill-icon-path (count "textures/")))]
          (comp/add-component! icon-w (comp/draw-texture icon-path 0xFFFFFFFF))
          (cgui-core/add-widget! panel icon-w))))
    ;; Learn button + dev state display (btn in scope for hide during dev)
    (let [btn (cgui-core/create-widget :pos [50 80] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Learn" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF)
          status-w (cgui-core/create-widget :pos [10 108] :size [180 14])
          status-tb (comp/text-box :text "" :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff)]
      (comp/add-component! btn btn-bg) (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (let [energy (double (or @(:energy container) 0.0))]
            (if (< energy est-consumption)
              (reset! should-rebuild false)
              (do (req-start-development! container :learn-skill {:skill-id (name skill-id)})
                  (reset! can-close? false))))))
      (cgui-core/add-widget! panel btn)
      (comp/add-component! status-w status-tb)
      (cgui-core/set-visible! status-w false) (cgui-core/add-widget! panel status-w)
      (let [prev-dev (atom false)]
        (events/on-frame cover
          (fn [_]
            (let [is-dev (boolean @(:is-developing container))
                  dev-prog (double (or @(:development-progress container) 0.0))
                  dev-complete (boolean @(:development-complete? container))]
              (cond is-dev
                    (do (cgui-core/set-visible! status-w true)
                        (comp/set-text! status-tb (str "Progress: " (int (* 100.0 (min 1.0 dev-prog))) "%"))
                        (comp/set-text-color! status-tb 0xFF25c4ff) (cgui-core/set-visible! btn false))
                    (and (not is-dev) @prev-dev dev-complete)
                    (do (comp/set-text! status-tb "Development succeeded!") (comp/set-text-color! status-tb 0xFF88FF88)
                        (cgui-core/set-visible! status-w true) (reset! should-rebuild true) (reset! can-close? true))
                    (and (not is-dev) @prev-dev (not dev-complete))
                    (do (comp/set-text! status-tb "Development failed") (comp/set-text-color! status-tb 0xFFFF5555)
                        (cgui-core/set-visible! status-w true) (reset! can-close? true))
                    :else (cgui-core/set-visible! status-w false))
              (reset! prev-dev is-dev))))))
    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Level-up overlay
;; ============================================================================

(defn- create-level-up-overlay!
  "Create overlay for ability level-up with Cover fade, ESC stack, dev state."
  [root container developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn [should-rebuild?]
                   (unregister-overlay! root end-cover!)
                   (end-cover!))
        _ (register-overlay! root end-cover!)
        dev-type (or (normalize-tier developer-type) :normal)
        dev-spec (developer/developer-spec dev-type)
        session-id (runtime-hooks/require-player-state-session-id "developer.panel")
        player (:player container)
        pstate (when player (store/get-player-state* session-id (uuid/player-uuid player)))
        ad (:ability-data pstate)
        current-level (int (or (:level ad) 1))
        target-level (inc current-level)
        est-consumption (long (* (:cps dev-spec 700.0) (+ 3 (* target-level target-level 0.5))))
        panel (create-centered-panel cover 180 140)
        bg (cgui-core/create-widget :pos [0 0] :size [180 140])
        should-rebuild (atom false) can-close? (atom true)]
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              [px py] (cgui-core/get-pos panel) [pw ph] (cgui-core/get-size panel)]
          (when (and @can-close? (or (< mx px) (> mx (+ px pw)) (< my py) (> my (+ py ph))))
            (close-fn @should-rebuild)))))
    (comp/add-component! bg (comp/draw-texture nil 0xC0202020))
    (cgui-core/add-widget! panel bg)
    ;; Title
    (let [title (cgui-core/create-widget :pos [10 8] :size [160 14])
          tb (comp/text-box :text (str "Level Up to Lv." target-level) :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb) (cgui-core/add-widget! panel title))
    ;; Energy estimate
    (let [energy-w (cgui-core/create-widget :pos [10 26] :size [160 12])
          energy-tb (comp/text-box :text (str "Req: " est-consumption " IF") :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF)]
      (comp/add-component! energy-w energy-tb) (cgui-core/add-widget! panel energy-w))
    ;; Confirm button
    ;; Confirm button + dev state display (btn in scope for hide during dev)
    (let [btn (cgui-core/create-widget :pos [40 50] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Confirm" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF)
          status-w (cgui-core/create-widget :pos [10 80] :size [160 14])
          status-tb (comp/text-box :text "" :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff)]
      (comp/add-component! btn btn-bg) (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (let [energy (double (or @(:energy container) 0.0))]
            (if (< energy est-consumption)
              (reset! should-rebuild false)
              (do (req-start-development! container :level-up)
                  (reset! can-close? false))))))
      (cgui-core/add-widget! panel btn)
      (comp/add-component! status-w status-tb)
      (cgui-core/set-visible! status-w false) (cgui-core/add-widget! panel status-w)
      (let [prev-dev (atom false)]
        (events/on-frame cover
          (fn [_]
            (let [is-dev (boolean @(:is-developing container))
                  dev-prog (double (or @(:development-progress container) 0.0))
                  dev-complete (boolean @(:development-complete? container))]
              (cond is-dev
                    (do (cgui-core/set-visible! status-w true)
                        (comp/set-text! status-tb (str "Progress: " (int (* 100.0 (min 1.0 dev-prog))) "%"))
                        (comp/set-text-color! status-tb 0xFF25c4ff) (cgui-core/set-visible! btn false))
                    (and (not is-dev) @prev-dev dev-complete)
                    (do (comp/set-text! status-tb "Level up successful!") (comp/set-text-color! status-tb 0xFF88FF88)
                        (cgui-core/set-visible! status-w true) (reset! should-rebuild true) (reset! can-close? true))
                    (and (not is-dev) @prev-dev (not dev-complete))
                    (do (comp/set-text! status-tb "Level up failed") (comp/set-text-color! status-tb 0xFFFF5555)
                        (cgui-core/set-visible! status-w true) (reset! can-close? true))
                    :else (cgui-core/set-visible! status-w false))
              (reset! prev-dev is-dev))))))
    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Right panel — mode dispatch
;; ============================================================================

(defn- player-holding-magnetic-coil? [player]
  (and player
       (satisfies? entity/IEntityOps player)
       (= special-items/magnetic-coil-item-id (entity/player-get-main-hand-item-id player))))

(defn- right-panel-mode
  "Pure: determine what to render in parent_right/area."
  [_player-state container player]
  (let [uuid-str (when player (uuid/player-uuid player))
        pstate (when uuid-str (store/get-player-state*
                                (runtime-hooks/require-player-state-session-id "developer.panel")
                                uuid-str))
        ad (:ability-data pstate)
        has-cat? (boolean (:category-id ad))
        holding-coil? (player-holding-magnetic-coil? player)]
    (cond
      (and has-cat? holding-coil?) :reset-console
      (not has-cat?) :console
      :else :skill-tree)))

(defn- render-skill-tree-area!
  "Render skill tree nodes in parent_right/area matching upstream SkillTree.scala."
  [root area-widget container player]
  (cgui-core/clear-widgets! area-widget)
  (try
    (let [uuid-str (when player (uuid/player-uuid player))
          session-id (runtime-hooks/require-player-state-session-id "developer.panel")
          pstate (when uuid-str (store/get-player-state* session-id uuid-str))
          dev-type (current-developer-type container)
          render-data (skill-tree/build-render-data-for-player-state pstate dev-type)
          nodes (:skill-nodes render-data)
          connections (:connections render-data)
          ;; Upstream constants: WidgetSize=16, TotalSize=23, IconSize=14, ProgSize=31
          widget-size 16 total-size 23 icon-sz 14 prog-sz 31
          draw-align (/ (- widget-size total-size) 2)
          prog-align (/ (- total-size prog-sz) 2)
          icon-align (/ (- total-size icon-sz) 2)
          skill-back-path (modid/asset-path "textures" "guis/developer/skill_back.png")
          skill-outline-path (modid/asset-path "textures" "guis/developer/skill_outline.png")
          bg-area-path (modid/asset-path "textures" "guis/effect/effect_developer_background.png")]

      ;; Parallax background (upstream: texAreaBack rawRect in FrameEvent)
      (let [bg-w (cgui-core/create-widget :pos [0 0] :size [(int 257) (int 139)])]
        (comp/add-component! bg-w (comp/draw-texture bg-area-path 0xFFFFFFFF))
        (cgui-core/add-widget! area-widget bg-w))

      ;; Connection lines (upstream: center=WidgetSize/2=8, inset=12.2, alpha=mAlpha*learned)
      (when (seq connections)
        (doseq [{:keys [from-x from-y to-x to-y child-learned? m-alpha]} connections]
          (let [dx (- to-x from-x) dy (- to-y from-y)
                norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
            (when (pos? norm)
              (let [ndx (/ dx norm) ndy (/ dy norm)
                    line-alpha (* (or m-alpha 0.7) (if child-learned? 1.0 0.4))
                    alpha-byte (int (* 255.0 line-alpha))
                    color (bit-or (bit-shift-left alpha-byte 24) 0xFFFFFF)
                    x0 (+ from-x (* ndx 12.2)) y0 (+ from-y (* ndy 12.2))
                    x1 (- to-x (* ndx 12.2)) y1 (- to-y (* ndy 12.2))
                    steps (max 1 (int norm))]
                (doseq [i (range (inc steps))]
                  (let [t (/ i (double steps))
                        px (int (+ x0 (* (- x1 x0) t)))
                        py (int (+ y0 (* (- y1 y0) t)))]
                    (when-let [dot-w (cgui-core/create-widget :pos [px py] :size [2 2])]
                      (comp/add-component! dot-w (comp/draw-texture nil color))
                      (cgui-core/add-widget! area-widget dot-w)))))))))

      ;; Skill nodes (upstream: no labels, WidgetSize=16 circle with outline + icon)
      (when (seq nodes)
        (doseq [{:keys [x y learned can-learn locked? skill-id skill-icon m-alpha]} nodes
                :when (and skill-id x y)]
          (let [node-x (int (+ x draw-align)) node-y (int (+ y draw-align))
                node-w (cgui-core/create-widget :pos [node-x node-y] :size [widget-size widget-size])
                eff-alpha (if locked? 0.25 (or m-alpha 0.7))
                node-alpha (int (* 255.0 eff-alpha))
                node-color (bit-or (bit-shift-left node-alpha 24)
                                   (cond learned 0x66CC66 can-learn 0x6699FF :else 0x444444))]
            ;; Circular skill_back
            (comp/add-component! node-w (comp/draw-texture skill-back-path node-color))
            ;; Dark skill_outline ring
            (let [ol-w (cgui-core/create-widget :pos [(+ node-x prog-align) (+ node-y prog-align)] :size [prog-sz prog-sz])
                  ol-alpha (int (* 255.0 (* eff-alpha 0.6)))
                  ol-color (bit-or (bit-shift-left ol-alpha 24) 0x333333)]
              (comp/add-component! ol-w (comp/draw-texture skill-outline-path ol-color))
              (cgui-core/add-widget! area-widget ol-w))
            ;; Skill icon centered
            (when skill-icon
              (let [icon-path (modid/asset-path "textures" (subs skill-icon (count "textures/")))
                    icon-alpha (if locked? (int (* 255.0 0.25)) 255)
                    icon-color (bit-or (bit-shift-left icon-alpha 24) 0xFFFFFF)
                    icon-w (cgui-core/create-widget :pos [(+ node-x icon-align) (+ node-y icon-align)] :size [icon-sz icon-sz])]
                (comp/add-component! icon-w (comp/draw-texture icon-path icon-color))
                (cgui-core/add-widget! area-widget icon-w)))
            ;; Click handler (locked + unlearned-without-prereqs don't respond)
            (when (and (or can-learn learned) (not locked?))
              (events/on-left-click node-w
                (fn [_] (create-skill-detail-overlay! root container skill-id dev-type))))
            (cgui-core/add-widget! area-widget node-w)))))
    (catch Exception e
      (do (log/error "Skill tree render failed:" (ex-message e))
          (log/stacktrace "Skill tree render failed" e)))))

(defn- make-dev-start-callback
  "Build a callback that writes server rejection reason to console state."
  [state-ref]
  (fn [resp]
    (when-not (:success resp)
      (when-let [state-a @state-ref]
        (let [err-msg (str "ERROR: " (:reason resp "unknown"))
              update-fn (fn [st]
                          (-> st
                              (update :lines #(-> % (conj err-msg) (clamp-lines)))
                              (assoc :phase :idle :dev-grace 0 :exec-cmd nil)))]
          (swap! state-a update-fn))))))

(defn- render-console-area!
  "Render text console in parent_right/area."
  [root area-widget container player mode]
  (cgui-core/clear-widgets! area-widget)
  (let [start-action (if (= :reset mode) :reset :level-up)
        state-ref (atom nil)
        on-start-dev (fn []
                       (req-start-development! container start-action nil
                         (make-dev-start-callback state-ref)))
        [state-a _panel] (dev-console/create-console area-widget
                           {:mode mode
                            :container container
                            :player-name (or @(:user-name container) "Player")
                            :focus-root root
                            :on-start-development on-start-dev})]
    (reset! state-ref state-a)))

;; ============================================================================
;; Wireless node label refresh
;; ============================================================================

(defn refresh-linked-node-label!
  "Send :list-nodes to server and update the wireless button label.
  Uses container owner for response routing (matching wireless overlay pattern)."
  [root container]
  (when (:tile-entity container)
    (let [payload (action-payload/action-payload container {})
          owner (try (container-state/owner-from-container container)
                     (catch Exception e
                       (log/error "[refresh-linked-node-label!] owner resolution error:" (ex-message e))
                       nil))
          _ (log/info "[refresh-linked-node-label!] sending list-nodes, owner=" (pr-str owner)
                      "payload=" (pr-str payload))]
      (net-client/send-to-server
        owner
        (dev-msg :list-nodes)
        payload
        (fn [resp]
          (log/info "[refresh-linked-node-label!] response received:" (pr-str resp))
          (when (map? resp)
            (let [text (if-let [n (:linked resp)]
                         (or (:node-name n) "N/A")
                         "N/A")]
              (log/info "[refresh-linked-node-label!] setting node label to:" text)
              (set-text-path! root "parent_left/panel_machine/button_wireless/text_nodename" text))))))))

;; ============================================================================
;; Main attach function — binds left and right panels
;; ============================================================================

(defn load-classic-developer-page
  "Return root `main` from `guis/rework/page_developer.xml`."
  []
  (try
    (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_developer.xml"))
          root (cgui-doc/get-widget doc "main")]
      (tech-ui/apply-breathe-to-ui-descendants! root)
      root)
    (catch Exception e
      (log/error "developer classic XML:" (ex-message e))
      (cgui-core/create-widget :name "main" :pos [0 0] :size [400 187]))))

(defn attach-classic-developer-bindings!
  "Attach handlers to all widgets in the classic developer page.

  Callbacks:
  - :on-wireless-click — called when wireless button clicked (for overlay)
  - :on-develop-start — (optional) overrides req-start-development!
    for portable/instant dev. Receives [container action extra]."
  [root container {:keys [on-wireless-click on-develop-start]}]
  (let [pl (:player container)]
    ;; Wireless node label
    (refresh-linked-node-label! root container)

    ;; btn_upgrade → level-up overlay (replaces separate screen navigation)
    (when-let [learn (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade")]
      (events/on-left-click learn
        (fn [_]
          (create-level-up-overlay! root container (current-developer-type container)))))

    ;; Wireless button → overlay callback.
    ;; deepest-only dispatch: clicks on text/icon children must forward to parent.
    (when-let [wbtn (cgui-core/find-widget root "parent_left/panel_machine/button_wireless")]
      (when on-wireless-click
        (let [forward (fn [_] (on-wireless-click))]
          (events/on-left-click wbtn forward)
          (doseq [child (cgui-core/get-widgets wbtn)]
            (events/on-left-click child forward)))))

    ;; Frame handler — updates left panel + right panel mode dispatch
    ;; + periodic wireless label refresh (every 5s for multiplayer correctness)
    (let [right-area (cgui-core/find-widget root "parent_right/area")
          last-mode (atom nil)
          refresh-tick (atom 0)]
      (events/on-frame root
        (fn [_]
          ;; Periodic wireless node label refresh (multiplayer safety)
          (swap! refresh-tick inc)
          (when (zero? (mod @refresh-tick 100))  ;; ~5s at 20tps
            (refresh-linked-node-label! root container))
          ;; Left panel updates
          (let [{:keys [ability-name icon-path exp-label level-label
                        cat-prog01 power01 sync-rate can-upgrade?]}
                (current-ui-model container pl)]
            (set-text-path! root "parent_left/panel_ability/text_abilityname" ability-name)
            (set-drawtexture-path! root "parent_left/panel_ability/logo_ability" icon-path)
            (set-text-path! root "parent_left/panel_ability/text_exp" exp-label)
            (set-text-path! root "parent_left/panel_ability/text_level" level-label)
            (set-progress-path! root "parent_left/panel_ability/logo_progress" cat-prog01)
            (set-progress-path! root "parent_left/panel_machine/progress_power" power01)
            ;; Sync rate uses fixed device property (not computed wireless ratio)
            (set-progress-path! root "parent_left/panel_machine/progress_syncrate"
              (double (or sync-rate 0.7)))
            (set-visible-path! root "parent_left/panel_ability/btn_upgrade" can-upgrade?)
            (set-visible-path! root "parent_left/panel_ability/text_level" (not can-upgrade?)))

          ;; Right panel mode dispatch
          (when right-area
            (let [mode (right-panel-mode nil container pl)]
              (when (not= mode @last-mode)
                (reset! last-mode mode)
                (case mode
                  :console (render-console-area! root right-area container pl :learn)
                  :reset-console (render-console-area! root right-area container pl :reset)
                  :skill-tree (render-skill-tree-area! root right-area container pl))))))))

    root))


