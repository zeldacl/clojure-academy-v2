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
  [container action & [extra callback]]
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
        (when callback (callback resp))))))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/namespaced-path (str/replace (subs icon-str (count "textures/")) ".png" ""))
      (modid/namespaced-path (str/replace icon-str ".png" "")))))

(defn- default-ability-icon-path []
  (modid/namespaced-path "guis/icons/icon_nocategory"))

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
                       (bal/clamp01 (/ level-prog thresh))
                       (if (>= lvl 5) 1.0 0.0))
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
                          :else (format "Level %d" lvl))]
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
  "Create a black fading overlay widget matching original Cover component
  (SkillTree.scala:897-927). Fade-in 0→0.7 alpha over 0.2s.
  Returns {:cover <widget> :end-cover! <fn>}."
  [parent root]
  (let [[pw ph] (cgui-core/get-size parent)
        cover (cgui-core/create-widget :pos [0 0] :size [pw ph])
        _ (comp/add-component! cover (comp/draw-texture nil (comp/mono-blend 0.0 0.0)))
        dt-comp (first (filter #(= (or (:kind %) (::kind %)) :drawtexture) @(:components cover)))
        state (atom {:start-time (time-secs) :ended? false :end-start-time 0.0})]
    ;; Fade animation frame handler
    (events/on-frame cover
      (fn [_]
        (let [{:keys [start-time ended? end-start-time]} @state
              t (time-secs)
              elapsed (- t (if ended? end-start-time start-time))
              src (min 1.0 (/ (max 0.0 elapsed) cover-fade-duration))
              alpha (if ended?
                      (* cover-max-alpha (- 1.0 src))
                      (* cover-max-alpha src))]
          ;; Update drawtexture color
          (when dt-comp
            (swap! (or (:state dt-comp) (atom {})) assoc :color (comp/mono-blend 0.0 (max 0.0 alpha))))
          ;; Full-screen resize to match root
          (let [[rw rh] (cgui-core/get-size root)]
            (cgui-core/set-size! cover rw rh))
          ;; Complete → remove
          (when (and ended? (<= alpha 0.0))
            (cgui-core/remove-widget! root cover)))))
    ;; End function (call to start fade-out)
    (let [end-fn (fn []
                   (when-not (:ended? @state)
                     (swap! state assoc :ended? true :end-start-time (time-secs))))]
      {:cover cover :end-cover! end-fn})))

(defn- register-overlay!
  "Register overlay end-fn on root's overlay stack for ESC handling."
  [root end-fn]
  (let [stack (or (:cover-end-fns @(:metadata root)) [])]
    (swap! (:metadata root) assoc :cover-end-fns (conj stack end-fn))))

(defn- unregister-overlay!
  "Remove overlay end-fn from root's overlay stack."
  [root end-fn]
  (swap! (:metadata root) update :cover-end-fns
         (fn [stack] (vec (remove #{end-fn} (or stack []))))))

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
  "Create overlay matching original AcademyCraft skillViewArea (SkillTree.scala:633-776):
  - 50x50 centered action icon with skill_back background + progress ring
  - Learned mode: EXP display, description
  - Unlearned mode: Not-learned text, condition icons, energy estimate, Learn button
  - Pre-click validation: energy, level, conditions
  - Development progress animation on action icon
  - Rebuild after successful learning"
  [root container skill-id _developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn [should-rebuild?]
                   (unregister-overlay! root end-cover!)
                   (end-cover!)
                   ;; Rebuild after successful learning (matching original RebuildEvent)
                   (when should-rebuild?
                     (when-let [area (cgui-core/find-widget root "parent_right/area")]
                       (render-skill-tree-area! root area container
                         (:player container)))))
        _ (register-overlay! root end-cover!)
        dev-type (or _developer-type :normal)
        dev-spec (developer/developer-spec dev-type)
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (:display-name skill-spec) (name skill-id) "Unknown")
        skill-level (or (:level skill-spec) 1)
        learned? (boolean (:learned? (skill-tree/build-skill-node-render-data
                                       {:skill skill-spec :x 0 :y 0 :idx 0
                                        :id skill-id :skill-id skill-id}
                                       nil dev-type)))
        ;; Energy estimation (matching original getEstimatedConsumption)
        est-consumption (long (* (:cps dev-spec 700.0)
                                (+ 3 (* skill-level skill-level 0.5))))
        panel (create-centered-panel cover 200 170)
        bg (cgui-core/create-widget :pos [0 0] :size [200 170])
        ;; Shared state
        should-rebuild (atom false)
        can-close? (atom true)
        dev-message (atom nil)
        dev-progress-atom (atom 0.0)
        skill-back-path (modid/namespaced-path "guis/developer/skill_back")
        skill-outline-path (modid/namespaced-path "guis/developer/skill_view_outline")
        skill-mask-path (modid/namespaced-path "guis/developer/skill_radial_mask")
        skill-icon-path (some-> (skill-query/get-skill-icon-path skill-id)
                                (modid/namespaced-path
                                  (str/replace (subs % (count "textures/")) ".png" "")))]
    ;; Close on click outside
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              [px py] (cgui-core/get-pos panel)
              [pw ph] (cgui-core/get-size panel)]
          (when (or (< mx px) (> mx (+ px pw)) (< my py) (> my (+ py ph)))
            (when @can-close? (close-fn @should-rebuild))))))
    ;; Background
    (comp/add-component! bg (comp/draw-texture nil 0xD0101010))
    (cgui-core/add-widget! panel bg)
    ;; ---- Action icon: 50x50 centered (matching drawActionIcon) ----
    (let [back-size 50 icon-sz 27 icon-align (/ (- back-size icon-sz) 2)
          icon-x (+ 75 0)  ;; centered in 200-wide panel
          icon-w (cgui-core/create-widget :pos [icon-x 8] :size [back-size back-size])]
      (comp/add-component! icon-w (comp/draw-texture skill-back-path 0xFFFFFFFF))
      ;; Skill icon on top
      (when skill-icon-path
        (let [inner-w (cgui-core/create-widget
                       :pos [(+ icon-x (int icon-align)) (+ 8 (int icon-align))]
                       :size [icon-sz icon-sz])]
          (comp/add-component! inner-w (comp/draw-texture skill-icon-path 0xFFFFFFFF))
          (cgui-core/add-widget! panel inner-w)))
      ;; Progress ring shader for development animation
      (let [prog-ring-w (cgui-core/create-widget :pos [icon-x 8] :size [back-size back-size])
            pq-comp (comp/shader-quad :shader-id :skill-progbar
                                      :texture-0 skill-outline-path
                                      :texture-1 skill-mask-path
                                      :progress 0.0)]
        (comp/add-component! prog-ring-w pq-comp)
        (cgui-core/set-visible! prog-ring-w false)
        (cgui-core/add-widget! panel prog-ring-w)
        ;; Animate progress ring during development
        (let [pq-state (comp/component-state pq-comp)]
          (events/on-frame cover
            (fn [_]
              (let [is-dev (boolean @(:is-developing container))
                    dev-prog (double (or @(:development-progress container) 0.0))]
                (when is-dev
                  (cgui-core/set-visible! prog-ring-w true)
                  (swap! pq-state assoc :progress (float (min 1.0 dev-prog)))
                  (reset! dev-progress-atom dev-prog)
                  (swap! dev-message
                         (fn [_] (str "Progress: " (int (* 100.0 (min 1.0 dev-prog))) "%"))))))))))
      (cgui-core/add-widget! panel icon-w))
    ;; ---- Text area ----
    (if learned?
      ;; === LEARNED MODE ===
      (do
        (let [title (cgui-core/create-widget :pos [10 62] :size [180 14])
              tb (comp/text-box :text skill-name :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
          (comp/add-component! title tb)
          (cgui-core/add-widget! panel title))
        (let [exp-text (str "EXP: " (int 0) "%")  ;; TODO: get actual skill exp
              exp-w (cgui-core/create-widget :pos [10 78] :size [180 12])
              tb (comp/text-box :text exp-text :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff)]
          (comp/add-component! exp-w tb)
          (cgui-core/add-widget! panel exp-w))
        (let [skill-desc (when (:description-key skill-spec) (i18n/translate (:description-key skill-spec)))
              desc (cgui-core/create-widget :pos [10 94] :size [180 28])
              tb (comp/text-box :text (or skill-desc "") :font :ac-normal :font-size 9 :align :center :color 0xFFDDDDDD)]
          (comp/add-component! desc tb)
          (cgui-core/add-widget! panel desc)))
      ;; === UNLEARNED MODE ===
      (do
        (let [title-text (str skill-name " (LV " skill-level ")")
              title (cgui-core/create-widget :pos [10 62] :size [180 14])
              tb (comp/text-box :text title-text :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
          (comp/add-component! title tb)
          (cgui-core/add-widget! panel title))
        (let [unlearned-w (cgui-core/create-widget :pos [10 78] :size [180 12])
              tb (comp/text-box :text "Not learned" :font :ac-normal :font-size 10 :align :center :color 0xFFFF5555)]
          (comp/add-component! unlearned-w tb)
          (cgui-core/add-widget! panel unlearned-w))
        ;; Condition icons
        (let [conditions (:conditions skill-spec)]
          (when (seq conditions)
            (let [n (count conditions) cond-step 16
                  start-x (int (- 100 (/ (* n cond-step) 2)))]
              (doseq [[idx cond-spec] (map-indexed vector conditions)]
                (let [cx (+ start-x (* idx cond-step))
                      cond-icon (cgui-core/create-widget :pos [cx 92] :size [14 14])
                      cond-color (case (:type cond-spec)
                                   :any-skill-level 0xFF4488FF
                                   :developer-type 0xFF44FF44
                                   :prerequisite 0xFFFF8844
                                   0xFF888888)]
                  (comp/add-component! cond-icon (comp/draw-texture nil cond-color))
                  (cgui-core/add-widget! panel cond-icon))))))
        ;; Energy estimate + learn message
        (let [req-w (cgui-core/create-widget :pos [10 108] :size [180 12])
              req-text (str "Req: " est-consumption " IF")
              msg-atom (atom req-text)]
          (comp/add-component! req-w
            (comp/text-box :text req-text :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF))
          (cgui-core/add-widget! panel req-w)
          ;; Frame handler: update message during development
          (events/on-frame req-w
            (fn [_]
              (when-let [msg @dev-message]
                (let [tb (comp/get-widget-component req-w :textbox)]
                  (comp/set-text! tb msg))))))
        ;; Learn button
        (let [btn (cgui-core/create-widget :pos [68 122] :size [64 16])
              btn-bg (comp/draw-texture nil 0xFF226622)
              btn-tb (comp/text-box :text "Learn" :font :ac-bold :font-size 9 :align :center :color 0xFFFFFFFF)]
          (comp/add-component! btn btn-bg)
          (comp/add-component! btn btn-tb)
          (events/on-left-click btn
            (fn [_]
              ;; Pre-click validation (matching original button handler)
              (let [energy (double (or @(:energy container) 0.0))]
                (cond
                  (< energy est-consumption)
                  (reset! dev-message "Not enough energy")
                  (> skill-level 5)
                  (reset! dev-message (str "Requires level " skill-level))
                  :else
                  (do
                    (req-start-development! container :learn-skill {:skill-id (name skill-id)})
                    (reset! can-close? false)
                    (swap! dev-message (fn [_] nil)))))))
          (cgui-core/add-widget! panel btn))
        ;; Development completion detection in frame handler
        (let [prev-dev (atom false)]
          (events/on-frame cover
            (fn [_]
              (let [is-dev (boolean @(:is-developing container))
                    dev-complete (boolean @(:development-complete? container))]
                (when (and (not is-dev) @prev-dev)
                  (if dev-complete
                    (do (reset! dev-message "Development succeeded!")
                        (reset! should-rebuild true))
                    (reset! dev-message "Development failed"))
                  (reset! can-close? true))
                (reset! prev-dev is-dev))))))))
    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Level-up overlay
;; ============================================================================

(defn- create-level-up-overlay!
  "Create overlay for ability level-up with Cover fade animation."
  [root container developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn []
                   (unregister-overlay! root end-cover!)
                   (end-cover!))
        _ (register-overlay! root end-cover!)
        panel (create-centered-panel cover 180 120)
        bg (cgui-core/create-widget :pos [0 0] :size [180 120])]
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              [px py] (cgui-core/get-pos panel)
              [pw ph] (cgui-core/get-size panel)]
          (when (or (< mx px) (> mx (+ px pw))
                    (< my py) (> my (+ py ph)))
            (close-fn)))))
    (comp/add-component! bg (comp/draw-texture nil 0xC0202020))
    (cgui-core/add-widget! panel bg)
    ;; Title
    (let [title (cgui-core/create-widget :pos [10 8] :size [160 14])
          tb (comp/text-box :text "Level Up" :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb)
      (cgui-core/add-widget! panel title))
    ;; Description
    (let [desc (cgui-core/create-widget :pos [10 28] :size [160 24])
          lvl (some-> (:tier container) deref normalize-tier)
          txt (format "Level up your ability.\nRequires %s developer or better." (name (or lvl :normal)))
          tb (comp/text-box :text txt :font :ac-normal :font-size 9 :align :center :color 0xFFCCCCCC)]
      (comp/add-component! desc tb)
      (cgui-core/add-widget! panel desc))
    ;; Confirm button
    (let [btn (cgui-core/create-widget :pos [40 72] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Confirm" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! btn btn-bg)
      (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (req-start-development! container :level-up)
          (close-fn)))
      (cgui-core/add-widget! panel btn))
    ;; Development state display (four-stage)
    (let [status-w (cgui-core/create-widget :pos [10 96] :size [160 14])
          status-tb (comp/text-box :text "" :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff)]
      (comp/add-component! status-w status-tb)
      (cgui-core/set-visible! status-w false)
      (cgui-core/add-widget! panel status-w)
      (let [prev-dev (atom false)]
        (events/on-frame cover
          (fn [_]
            (let [is-dev (boolean @(:is-developing container))
                  dev-prog (double (or @(:development-progress container) 0.0))
                  dev-complete (boolean @(:development-complete? container))]
              (cond
                is-dev
                (do
                  (cgui-core/set-visible! status-w true)
                  (comp/set-text! status-tb (str "Progress: " (int (* 100.0 (min 1.0 dev-prog))) "%"))
                  (comp/set-text-color! status-tb 0xFF25c4ff)
                  (cgui-core/set-visible! btn false))
                (and (not is-dev) @prev-dev dev-complete)
                (do
                  (comp/set-text! status-tb "Level up successful")
                  (comp/set-text-color! status-tb 0xFF88FF88)
                  (cgui-core/set-visible! status-w true)
                  (cgui-core/set-visible! btn false))
                (and (not is-dev) @prev-dev (not dev-complete))
                (do
                  (comp/set-text! status-tb "Level up failed")
                  (comp/set-text-color! status-tb 0xFFFF5555)
                  (cgui-core/set-visible! status-w true)
                  (cgui-core/set-visible! btn false))
                :else
                (do
                  (cgui-core/set-visible! status-w false)
                  (cgui-core/set-visible! btn true)))
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
  "Render skill tree nodes in parent_right/area matching original AcademyCraft
  SkillTree.scala visual style:
  - Connection lines as textured line.png quads
  - Skill nodes with skill_back + skill_outline two-layer backgrounds
  - m-alpha applied for learnability-based transparency
  - Node labels with matching font"
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
          ;; Texture paths matching original AcademyCraft
          skill-back-path (modid/namespaced-path "guis/developer/skill_back")
          skill-outline-path (modid/namespaced-path "guis/developer/skill_outline")
          line-tex-path (modid/namespaced-path "guis/developer/line")
          ;; Sizing constants matching SkillTree.scala:267-273
          widget-size 16.0 total-size 23.0 prog-size 31.0 icon-sz 14.0
          prog-align (/ (- total-size prog-size) 2)   ;; -4.0
          icon-align (/ (- total-size icon-sz) 2)      ;; 4.5
          draw-align (/ (- widget-size total-size) 2)] ;; -3.5
      ;; ---- Connection lines: textured quads using line.png ----
      ;; Original uses GL_QUADS with line.png texture; we approximate with
      ;; 6x6 textured segments at sub-4px spacing along each connection.
      (when (seq connections)
        (doseq [{:keys [from-x from-y to-x to-y satisfied? locked?]} connections]
          (let [color (cond locked?     0x60444444
                            satisfied?  0xB4A7FF7A
                            :else       0x80999999)
                dx (- to-x from-x)
                dy (- to-y from-y)
                dist (Math/sqrt (+ (* dx dx) (* dy dy)))
                steps (max 2 (int (/ dist 3.5)))]
            (doseq [i (range steps)]
              (let [t (/ (+ i 0.5) (double steps))
                    cx (+ from-x (* dx t))
                    cy (+ from-y (* dy t))
                    bar-w (cgui-core/create-widget
                           :pos [(int (- cx 3)) (int (- cy 3))]
                           :size [6 6])]
                (comp/add-component! bar-w (comp/draw-texture line-tex-path color))
                (cgui-core/add-widget! area-widget bar-w))))))
          ;; ---- Skill nodes with entry animation, hover scale, two-layer backgrounds ----
          (when (seq nodes)
            (let [area-create-time (System/currentTimeMillis)
                  bg-tex-path (modid/namespaced-path "guis/effect/effect_developer_background")
                  bg-widget (cgui-core/create-widget :pos [0 0] :size [257 139])]
              (comp/add-component! bg-widget (comp/draw-texture bg-tex-path 0x22FFFFFF))
              (cgui-core/add-widget! area-widget bg-widget)
              (doseq [{:keys [x y learned can-learn skill-id skill-name
                              skill-icon m-alpha] :as _nd} nodes
                      :when (and skill-id x y)]
                (let [idx (or (:idx _nd) 0)
                      node-x (int (+ x draw-align))
                      node-y (int (+ y draw-align))
                      total-size (int total-size)
                      bg-base (if learned 0xFF66CC66 (if can-learn 0xFF6699FF 0xFF444444))
                      bg-a (int (* 255 m-alpha))
                      bg-color (bit-or (bit-and bg-base 0x00FFFFFF) (bit-shift-left bg-a 24))
                      outline-a (int (* 255 m-alpha 0.6))
                      outline-color (bit-or 0x00333333 (bit-shift-left outline-a 24))
                      icon-a (if (or learned can-learn) 255 (int (* 255 m-alpha)))
                      icon-color (bit-or 0x00FFFFFF (bit-shift-left icon-a 24))
                      blend-offset (+ (* idx 0.08) 0.1)]
                  ;; Outline ring (behind)
                  (let [outline-w (cgui-core/create-widget
                                   :pos [(+ node-x (int prog-align)) (+ node-y (int prog-align))]
                                   :size [(int prog-size) (int prog-size)])]
                    (comp/add-component! outline-w (comp/draw-texture skill-outline-path outline-color))
                    (cgui-core/add-widget! area-widget outline-w))
                  ;; Back texture + icon + label on node widget
                  (let [node-w (cgui-core/create-widget
                                :pos [node-x node-y]
                                :size [total-size total-size])
                        tf (comp/transform :scale-x 1.0 :scale-y 1.0)
                        _ (swap! (:metadata node-w) assoc :anim-state anim-state)]
                    (comp/add-component! node-w tf)
                    (comp/add-component! node-w (comp/draw-texture skill-back-path bg-color))
                          ;; Skill icon (mono shader for unlearned icons)
                    (when skill-icon
                      (let [icon-path (modid/namespaced-path
                                       (str/replace (subs skill-icon (count "textures/")) ".png" ""))]
                        (let [icon-w (cgui-core/create-widget
                                      :pos [(+ node-x (int icon-align)) (+ node-y (int icon-align))]
                                      :size [(int icon-sz) (int icon-sz)])]
                          (if (and (not learned) (not can-learn))
                            (comp/add-component! icon-w
                              (comp/shader-quad :shader-id :mono
                                                :texture-0 icon-path :texture-1 nil :progress 0.0))
                            (comp/add-component! icon-w (comp/draw-texture icon-path icon-color)))
                          (cgui-core/add-widget! area-widget icon-w))))
                    ;; Progress ring shader on learned nodes
                    (when learned
                      (let [view-outline-path (modid/namespaced-path "guis/developer/skill_view_outline")
                            mask-path (modid/namespaced-path "guis/developer/skill_radial_mask")
                            prog-w (cgui-core/create-widget
                                    :pos [(+ node-x (int prog-align)) (+ node-y (int prog-align))]
                                    :size [(int prog-size) (int prog-size)])
                            {:keys [exp]} _nd
                            skill-exp (double (or exp 0.0))]
                        (comp/add-component! prog-w
                          (comp/shader-quad :shader-id :skill-progbar
                                            :texture-0 view-outline-path
                                            :texture-1 mask-path
                                            :progress skill-exp))
                        (cgui-core/add-widget! area-widget prog-w)))
                    ;; Label
                    (let [label (cgui-core/create-widget
                                 :pos [(+ node-x total-size 4) (+ node-y 8)]
                                 :size [120 12])]
                      (comp/add-component! label (comp/text-box :text (str skill-name) :font :ac-normal
                                                                :font-size 9 :align :left :color 0xFFFFFFFF))
                      (cgui-core/add-widget! area-widget label))
                    ;; Entry animation: staggered fade-in per-node
                    (events/on-frame node-w
                      (fn [_]
                        (let [dt-sec (max 0.0 (/ (- (System/currentTimeMillis) area-create-time) 1000.0))
                              dt (- dt-sec blend-offset)
                              back-alpha (* m-alpha (min 1.0 (max 0.0 (* dt 10.0))))
                              icon-a2 (* m-alpha (min 1.0 (max 0.0 (* (- dt 0.08) 10.0))))
                              bg-a2 (int (* 255 back-alpha))
                              bg-new (bit-or (bit-and bg-base 0x00FFFFFF) (bit-shift-left bg-a2 24))]
                          (when-let [dt-comp (first (filter #(= (or (:kind %) (::kind %)) :drawtexture) @(:components node-w)))]
                            (swap! (or (:state dt-comp) (atom {})) assoc :color bg-new)))))
                    ;; Click handler
                    (when (or can-learn learned)
                      (events/on-left-click node-w
                        (fn [_] (create-skill-detail-overlay! root container skill-id dev-type))))
                    (cgui-core/add-widget! area-widget node-w))))
              ;; Area-level frame: parallax background + hover hit-testing
              (events/on-frame area-widget
                (fn [_]
                  (let [root-meta @(:metadata root)
                        mx (double (or (:last-mouse-x root-meta) 0.0))
                        my (double (or (:last-mouse-y root-meta) 0.0))
                        ;; Parallax offset
                        [ax ay] (cgui-core/get-pos area-widget)
                        lx (- mx ax) ly (- my ay)
                        max-du 0.01 max-du-skills 10.0
                        dx (- (min 1.0 (max 0.0 (/ mx 400.0))) 0.5)
                        dy (- (min 1.0 (max 0.0 (/ my 187.0))) 0.5)
                        bg-u (+ 0.5 (* dx max-du))
                        bg-v (+ 0.5 (* dy max-du))
                        scl (/ 1.0 1.01)]
                    ;; Update parallax background UV
                    (when-let [bg-dt-comp (first (filter #(= (or (:kind %) (::kind %)) :drawtexture) @(:components bg-widget)))]
                      (swap! (or (:state bg-dt-comp) (atom {})) assoc
                             :uv [(float bg-u) (float bg-v) (float scl) (float scl)]))
                    ;; Hover hit-test for node widgets (matching SkillTree.scala hover scale logic)
                    (doseq [child (cgui-core/get-widgets area-widget)]
                      (when-let [anim-state (get @(:metadata child) :anim-state)]
                        (when-let [an @anim-state]
                          (let [tf-comp (first (filter #(= (or (:kind %) (::kind %)) :transform) @(:components child)))
                                [cx cy] (cgui-core/get-pos child)
                                [cw ch] (cgui-core/get-size child)
                                within? (and (>= lx cx) (<= lx (+ cx cw))
                                             (>= ly cy) (<= ly (+ cy ch)))
                                now-ms (System/currentTimeMillis)]
                            (when tf-comp
                              (if within?
                                (when (not= (:hover-state an) :hover)
                                  (swap! anim-state assoc :hover-state :hover :last-transit-time now-ms))
                                (when (not= (:hover-state an) :idle)
                                  (swap! anim-state assoc :hover-state :idle :last-transit-time now-ms)))
                              ;; Apply hover scale lerp via transform component
                              (let [{:keys [hover-state last-transit-time]} an
                                    t (max 0.0 (/ (- now-ms last-transit-time) 1000.0))
                                    transit (min 1.0 (/ t 0.1))
                                    scale (if (= :hover hover-state)
                                            (+ 1.0 (* 0.2 transit))
                                            (+ 1.2 (* -0.2 transit)))]
                                (swap! (comp/component-state tf-comp) assoc :scale-x scale :scale-y scale))))))))))))))
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
  - :on-wireless-click — called when wireless button clicked (for overlay)"
  [root container {:keys [on-wireless-click]}]
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
    (let [right-area (cgui-core/find-widget root "parent_right/area")
          last-mode (atom nil)]
      (events/on-frame root
        (fn [_]
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
            (set-visible-path! root "parent_left/panel_ability/btn_upgrade" can-upgrade?))

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


