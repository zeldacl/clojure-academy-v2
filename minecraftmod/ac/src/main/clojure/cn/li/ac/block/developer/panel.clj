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
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
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
            [cn.li.ac.ability.model.ability :as adata]
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
  "Create overlay matching original skillViewArea: skill_back+icon+ring centered on
  the fading cover, text area at center+25px below, learned/unlearned mode split."
  [root container skill-id _developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        close-fn (fn []
                   (unregister-overlay! root end-cover!)
                   (end-cover!))
        _ (register-overlay! root end-cover!)
        dev-type (or _developer-type :normal)
        dev-spec (developer/developer-spec dev-type)
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")
        skill-level (int (or (:level skill-spec) 1))
        est-consumption (long (* (:cps dev-spec 700.0)
                                 (+ 3 (* skill-level skill-level 0.5))))
        session-id (runtime-hooks/require-player-state-session-id "developer.panel")
        uuid-str (when (:player container) (uuid/player-uuid (:player container)))
        pstate (when uuid-str (store/get-player-state* session-id uuid-str))
        ad (:ability-data pstate)
        learned? (adata/is-learned? ad skill-id)
        skill-exp (double (if learned? (or (adata/get-skill-exp ad skill-id) 0.0) 0.0))
        player-level (int (or (:level ad) 1))
        ;; Layout — cover is 400×187, cy=93
        ;; Upstream: skillWid.centered().size(50,50)
        ;; textArea: centered, pos(0,25) → textBase = cy + 20
        ;; Text lines: (0,3) title, (0,15) not-learned/EXP, (0,25) cond icons, (0,40) message, (0,55) button
        cx 200 cy 93
        back-sz 50 icon-sz 27
        icon-ofs (/ (- back-sz icon-sz) 2)
        back-x (- cx 25) back-y (- cy 25)
        icon-x (+ back-x icon-ofs) icon-y (+ back-y icon-ofs)
        text-base (+ cy 20)
        title-y   (+ text-base 3)
        info-y    (+ text-base 15)
        cond-y    (+ text-base 25)
        msg-y     (+ text-base 40)
        btn-y     (+ text-base 55)
        btn-x     (- cx 16)
        ;; Textures
        outline-base (modid/asset-path "textures" "guis/developer/skill_view_outline.png")
        outline-glow (modid/asset-path "textures" "guis/developer/skill_view_outline_glow.png")
        mask-path    (modid/asset-path "textures" "guis/developer/skill_radial_mask.png")
        skill-back-path (modid/asset-path "textures" "guis/developer/skill_back.png")
        button-tex-path (modid/asset-path "textures" "guis/developer/button.png")
        skill-icon-path (when-let [ip (skill-query/get-skill-icon-path skill-id)]
                          (modid/asset-path "textures" (subs ip (count "textures/"))))
        ;; i18n
        skill-not-learned-text (i18n/translate "skill_tree.my_mod.skill_not_learned")
        req-text   (i18n/translate "skill_tree.my_mod.req")
        learn-q-text (format (i18n/translate "skill_tree.my_mod.learn_question") (format "%.0f" (double est-consumption)))
        message-atom (atom learn-q-text)
        progress-atom (atom 0.0)
        ring-comp (comp/shader-ring {:texture-0 outline-base
                                     :texture-1 mask-path
                                     :progress 0.0})
        can-close? (atom true)
        msg-tb-ref (atom nil)
        ;; Prerequisites as condition icons
        prerequisites (or (:prerequisites skill-spec) [])
        cond-icon-sz 14
        cond-step 16
        cond-len (* cond-step (count prerequisites))
        cond-start-x (- (/ cond-len 2))]

    ;; Close on cover click — guard button area so button click doesn't also close
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              on-btn? (and (>= mx btn-x) (<= mx (+ btn-x 32))
                           (>= my btn-y) (<= my (+ btn-y 16)))]
          (when (and @can-close? (not (and (not learned?) on-btn?)))
            (close-fn)))))

    ;; skill_back (50×50, centered) — matching texSkillBack in drawActionIcon
    (let [sb-w (cgui-core/create-widget :pos [back-x back-y] :size [back-sz back-sz])]
      (comp/add-component! sb-w (comp/draw-texture skill-back-path 0xFFFFFFFF))
      (cgui-core/add-widget! cover sb-w))

    ;; Skill icon (27×27 at offset 11 inside skill_back)
    (when skill-icon-path
      (let [ic-w (cgui-core/create-widget :pos [icon-x icon-y] :size [icon-sz icon-sz])
            icon-color (if learned? 0xFFFFFFFF 0xFF888888)]
        (comp/add-component! ic-w (comp/draw-texture skill-icon-path icon-color))
        (cgui-core/add-widget! cover ic-w)))

    ;; Shader ring (50×50 overlaid) — progress=0 for learned in popup (original behavior)
    (let [ring-w (cgui-core/create-widget :pos [back-x back-y] :size [back-sz back-sz])]
      (comp/add-component! ring-w ring-comp)
      (cgui-core/add-widget! cover ring-w))

    ;; Title — upstream: learned = just name, unlearned = "Name (LV X)"
    (let [title-text (if learned? skill-name (str skill-name " (LV " skill-level ")"))
          title-w (cgui-core/create-widget :pos [0 title-y] :size [400 14])
          title-tb (comp/text-box :text title-text :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title-w title-tb)
      (cgui-core/add-widget! cover title-w))

    (if learned?
      ;; ── Learned mode: EXP + description ──────────────────────────────────
      (do
        ;; EXP line (foSkillProg: 8, CENTER, 0xFFa1e1ff)
        (let [exp-text (str (i18n/translate "skill_tree.my_mod.skill_exp") " " (int (* 100.0 skill-exp)) "%")
              exp-w (cgui-core/create-widget :pos [0 info-y] :size [400 12])
              exp-tb (comp/text-box :text exp-text :font :ac-normal :font-size 8 :align :center :color 0xFFa1e1ff)]
          (comp/add-component! exp-w exp-tb)
          (cgui-core/add-widget! cover exp-w))
        ;; Description (foSkillDesc: 9, CENTER)
        (when (and skill-spec (:description-key skill-spec))
          (let [desc-w (cgui-core/create-widget :pos [0 cond-y] :size [400 12])
                desc-tb (comp/text-box :text (i18n/translate (:description-key skill-spec))
                                       :font :ac-normal :font-size 9 :align :center :color 0xFFDDDDDD)]
            (comp/add-component! desc-w desc-tb)
            (cgui-core/add-widget! cover desc-w))))

      ;; ── Unlearned mode ───────────────────────────────────────────────────
      (let [btn (cgui-core/create-widget :pos [btn-x btn-y] :size [32 16])
            btn-tex (comp/draw-texture button-tex-path 0xFFFFFFFF)
            btn-tb (comp/text-box :text "LEARN" :font :ac-bold :font-size 9 :align :center :color 0xFF101010)]

        ;; "Not learned" — foSkillUnlearned (10, CENTER, red)
        (let [nl-w (cgui-core/create-widget :pos [0 info-y] :size [400 12])
              nl-tb (comp/text-box :text skill-not-learned-text :font :ac-normal :font-size 10 :align :center :color 0xFFff5555)]
          (comp/add-component! nl-w nl-tb)
          (cgui-core/add-widget! cover nl-w))

        ;; Req label (foSkillReq: 9, RIGHT)
        (let [req-w (cgui-core/create-widget :pos [0 cond-y] :size [400 12])
              req-tb (comp/text-box :text (str req-text " " (format "%.0f" (double est-consumption)))
                                    :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF)]
          (comp/add-component! req-w req-tb)
          (cgui-core/add-widget! cover req-w))

        ;; Condition icons (upstream: skill.getDevConditions → 14×14 icons row)
        (when (seq prerequisites)
          (doseq [[idx {:keys [skill-id min-exp]}] (map-indexed vector prerequisites)]
            (let [icon-path (when-let [ip (skill-query/get-skill-icon-path skill-id)]
                             (modid/asset-path "textures" (subs ip (count "textures/"))))
                  accepted? (>= (double (or (adata/get-skill-exp ad skill-id) 0.0)) (double min-exp))
                  cx-cond (+ cx cond-start-x (* idx cond-step))
                  cond-w (cgui-core/create-widget :pos [cx-cond cond-y] :size [cond-icon-sz cond-icon-sz])
                  cond-color (if accepted? 0xFFFFFFFF 0xFF888888)]
              (when icon-path
                (comp/add-component! cond-w (comp/draw-texture icon-path cond-color))
                (cgui-core/add-widget! cover cond-w)))))

        ;; learn_question / message — foSkillUnlearned2 (10, CENTER), dynamic
        (let [msg-w (cgui-core/create-widget :pos [0 msg-y] :size [400 12])
              msg-tb (comp/text-box :text @message-atom :font :ac-normal :font-size 10 :align :center :color 0xAAFFFFFF)]
          (reset! msg-tb-ref msg-tb)
          (comp/add-component! msg-w msg-tb)
          (cgui-core/add-widget! cover msg-w))

        ;; LEARN button: button.png 32×16
        (comp/add-component! btn btn-tex)
        (comp/add-component! btn btn-tb)
        (events/on-left-click btn
          (fn [_]
            (let [energy (double (or @(:energy container) 0.0))]
              (cond
                (< energy est-consumption)
                (reset! message-atom (i18n/translate "skill_tree.my_mod.noenergy"))
                (> skill-level player-level)
                (reset! message-atom (format (i18n/translate "skill_tree.my_mod.level_fail") skill-level))
                (not (learning-rules/can-learn? skill-spec ad player-level dev-type))
                (reset! message-atom (i18n/translate "skill_tree.my_mod.condition_fail"))
                :else
                (do (req-start-development! container :learn-skill {:skill-id (name skill-id)})
                    (reset! can-close? false)))))
        (cgui-core/add-widget! cover btn))

        ;; Dev progress monitor — updates ring + message each frame (upstream: FrameEvent on ret)
        (let [prev-dev (atom false)]
          (events/on-frame cover
            (fn [_]
              (let [is-dev (boolean @(:is-developing container))
                    dev-prog (double (or @(:development-progress container) 0.0))
                    dev-complete (boolean @(:development-complete? container))
                    prog01 (float (if is-dev (min 1.0 dev-prog) (float @progress-atom)))
                    glow? (>= (double prog01) 0.999)]
                ;; Ring tracks dev progress + glow texture (upstream: drawActionIcon glow)
                (swap! (:state ring-comp) assoc
                       :progress prog01
                       :texture-0 (if glow? outline-glow outline-base))
                ;; Message replaces learn_question (upstream: message var)
                (cond
                  is-dev
                  (let [pct (int (* 100.0 (min 1.0 dev-prog)))]
                    (reset! message-atom (str (i18n/translate "skill_tree.my_mod.progress") " " pct "%")))
                  (and (not is-dev) @prev-dev dev-complete)
                  (do (reset! message-atom (i18n/translate "skill_tree.my_mod.dev_successful"))
                      (reset! progress-atom 1.0)
                      (reset! can-close? true))
                  (and (not is-dev) @prev-dev (not dev-complete))
                  (do (reset! message-atom (i18n/translate "skill_tree.my_mod.dev_failed"))
                      (reset! can-close? true))
                  :else nil)
                ;; Push updated message to textbox
                (when-let [tb @msg-tb-ref]
                  (comp/set-text! tb @message-atom))
                (reset! prev-dev is-dev)))))))

    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Level-up overlay
;; ============================================================================

(defn- create-level-up-overlay!
  "Create overlay for ability level-up matching upstream levelUpArea.
  Layout: cover → skill_back(50×50 centered) + icon(27×27) + shader ring
         → textArea(centered, +25px) → button(32×16, centered, +40px)
  i18n keys: skill_tree.my_mod.uplevel / req / level_question / noenergy / dev_*"
  [root container developer-type]
  (let [{:keys [cover end-cover!]} (create-fading-cover root root)
        should-rebuild (atom false) can-close? (atom true)
        close-fn (fn []
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
        ;; Cover dimensions (fills root)
        cw (nth (cgui-core/get-size root) 0)
        ch (nth (cgui-core/get-size root) 1)
        ;; Centered icon widget 50×50 — matching upstream wid.centered().size(50,50)
        icon-back-sz 50
        icon-inner-sz 27
        icon-ofs (/ (- icon-back-sz icon-inner-sz) 2)
        icon-cx (int (/ cw 2))
        icon-cy (int (/ ch 2))
        icon-x (- icon-cx 25)
        icon-y (- icon-cy 25)
        icon-inner-x (+ icon-x icon-ofs)
        icon-inner-y (+ icon-y icon-ofs)
        ;; Text area: centered, pos(0, 25) → textY = cy + 25
        text-base-y (+ icon-cy 25)
        ;; Button: centered, pos(0, 40) → btnY = textArea center + 40 ≈ cy + 25 + 40
        btn-sz 32
        btn-x (- icon-cx (/ btn-sz 2))
        btn-y (+ text-base-y 40)
        ;; Textures
        skill-back-path (modid/asset-path "textures" "guis/developer/skill_back.png")
        outline-path (modid/asset-path "textures" "guis/developer/skill_view_outline.png")
        mask-path (modid/asset-path "textures" "guis/developer/skill_radial_mask.png")
        button-tex-path (modid/asset-path "textures" "guis/developer/button.png")
        ;; Condition icon — upstream: Resources.getTexture("abilities/condition/any" + (level+1))
        condition-icon-key (str "abilities/condition/any" target-level)
        condition-icon-path (modid/asset-path "textures" (str condition-icon-key ".png"))
        ;; i18n
        lvltext (format (i18n/translate "skill_tree.my_mod.uplevel") (str "Lv." target-level))
        reqtext (str (i18n/translate "skill_tree.my_mod.req") " " (format "%.0f" (double est-consumption)))
        hint-atom (atom (i18n/translate "skill_tree.my_mod.level_question"))
        progress-atom (atom 0.0)
        ;; Shader ring component (shared, updated on-frame)
        ring-comp (comp/shader-ring {:texture-0 outline-path
                                     :texture-1 mask-path
                                     :progress 0.0})
        prev-dev (atom false)
        hint-tb-ref (atom nil)    ;; holds the dynamic hint textbox for on-frame updates
        ;; Helper: build the overlay widgets (closes over let bindings)
        build-overlay-widgets! (fn []
                                 ;; ── Close on cover click (outside icon area) ──
                                 (events/on-left-click cover
                                   (fn [evt]
                                     (let [mx (int (:x evt 0)) my (int (:y evt 0))
                                           on-btn? (and (>= mx btn-x) (<= mx (+ btn-x btn-sz))
                                                        (>= my btn-y) (<= my (+ btn-y 16)))]
                                       (when (and @can-close? (not on-btn?))
                                         (close-fn)))))
                                 ;; ── skill_back 50×50 centered ──
                                 (let [sb-w (cgui-core/create-widget :pos [icon-x icon-y] :size [icon-back-sz icon-back-sz])]
                                   (comp/add-component! sb-w (comp/draw-texture skill-back-path 0xFFFFFFFF))
                                   (cgui-core/add-widget! cover sb-w))
                                 ;; ── Condition icon 27×27 at offset IconAlign ──
                                 (let [ic-w (cgui-core/create-widget :pos [icon-inner-x icon-inner-y] :size [icon-inner-sz icon-inner-sz])]
                                   (comp/add-component! ic-w (comp/draw-texture condition-icon-path 0xFFFFFFFF))
                                   (cgui-core/add-widget! cover ic-w))
                                 ;; ── Shader ring 50×50 (overlaid on icon, progress driven by dev state) ──
                                 (let [ring-w (cgui-core/create-widget :pos [icon-x icon-y] :size [icon-back-sz icon-back-sz])]
                                   (comp/add-component! ring-w ring-comp)
                                   (cgui-core/add-widget! cover ring-w))
                                 ;; ── Text area: three lines matching upstream ──
                                 (let [w (cgui-core/create-widget :pos [0 (+ text-base-y 3)] :size [cw 14])
                                       tb (comp/text-box :text lvltext :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
                                   (comp/add-component! w tb) (cgui-core/add-widget! cover w))
                                 (let [w (cgui-core/create-widget :pos [0 (+ text-base-y 16)] :size [cw 12])
                                       tb (comp/text-box :text reqtext :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF)]
                                   (comp/add-component! w tb) (cgui-core/add-widget! cover w))
                                 (let [w (cgui-core/create-widget :pos [0 (+ text-base-y 26)] :size [cw 12])
                                       hint-tb (comp/text-box :text @hint-atom :font :ac-normal :font-size 9 :align :center :color 0xAAFFFFFF)]
                                   (reset! hint-tb-ref hint-tb)
                                   (comp/add-component! w hint-tb) (cgui-core/add-widget! cover w))
                                 ;; ── Button: newButton() — 32×16 texButton + text ──
                                 (let [btn-w (cgui-core/create-widget :pos [btn-x btn-y] :size [btn-sz 16])
                                       btn-tex (comp/draw-texture button-tex-path 0xFFFFFFFF)
                                       btn-tb (comp/text-box :text "LEARN" :font :ac-bold :font-size 9 :align :center :color 0xFF101010)]
                                   (comp/add-component! btn-w btn-tex)
                                   (comp/add-component! btn-w btn-tb)
                                   (events/on-left-click btn-w
                                     (fn [_]
                                       (let [energy (double (or @(:energy container) 0.0))]
                                         (if (< energy est-consumption)
                                           (reset! hint-atom (i18n/translate "skill_tree.my_mod.noenergy"))
                                           (do (req-start-development! container :level-up)
                                               (reset! can-close? false))))))
                                   (cgui-core/add-widget! cover btn-w))
                                 ;; ── Dev state monitor (FrameEvent on cover) ──
                                 (events/on-frame cover
                                   (fn [_]
                                     (let [is-dev (boolean @(:is-developing container))
                                           dev-prog (double (or @(:development-progress container) 0.0))
                                           dev-complete (boolean @(:development-complete? container))]
                                       (swap! (:state ring-comp) assoc :progress (if is-dev (float dev-prog) (float @progress-atom)))
                                       (cond
                                         is-dev
                                         (do (reset! hint-atom (i18n/translate "skill_tree.my_mod.dev_developing"))
                                             (reset! progress-atom dev-prog))
                                         (and (not is-dev) @prev-dev dev-complete)
                                         (do (reset! hint-atom (i18n/translate "skill_tree.my_mod.dev_successful"))
                                             (reset! progress-atom 1.0)
                                             (reset! can-close? true)
                                             (reset! should-rebuild true))
                                         (and (not is-dev) @prev-dev (not dev-complete))
                                         (do (reset! hint-atom (i18n/translate "skill_tree.my_mod.dev_failed"))
                                             (reset! can-close? true))
                                         :else nil)
                                       (when-let [tb @hint-tb-ref]
                                         (comp/set-text! tb @hint-atom))
                                       (reset! prev-dev is-dev)))))]

    (build-overlay-widgets!)
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

;; ============================================================================
;; Skill tree rendering — upstream SkillTree.scala matching
;; ============================================================================

(def ^:private skill-tree-creation-time (atom nil))
(def ^:private skill-tree-hover (atom {:hover-idx nil :prev-idx nil :start 0}))

(defn- clamp01 [x] (max 0.0 (min 1.0 (double x))))
(defn- lerp [a b t] (+ a (* (- b a) (clamp01 t))))

;; Upstream constants
(def ^:private widget-size 16)
(def ^:private total-size 23)
(def ^:private icon-sz 14)
(def ^:private prog-sz 31)
(def ^:private draw-align (/ (- widget-size total-size) 2))
(def ^:private prog-align (/ (- total-size prog-sz) 2))
(def ^:private icon-align (/ (- total-size icon-sz) 2))


(defn- back-alpha [anim-time idx m-alpha]
  (* m-alpha (clamp01 (* (- anim-time (* idx 0.08) 0.1) 10.0))))

(defn- icon-alpha [anim-time idx m-alpha]
  (* m-alpha (clamp01 (* (- anim-time (* idx 0.08) 0.18) 10.0))))

(defn- line-blend [anim-time child-idx]
  (clamp01 (* (- anim-time (* (or child-idx 0) 0.08) 0.1) 5.0)))

(defn- progress-blend [anim-time idx]
  (clamp01 (* (- anim-time (* idx 0.08) 0.22) 2.0)))

(defn- skill-tree-textures []
  {:skill-back (modid/asset-path "textures" "guis/developer/skill_back.png")
   :skill-outline (modid/asset-path "textures" "guis/developer/skill_outline.png")
   :bg-area (modid/asset-path "textures" "guis/effect/effect_developer_background.png")})

(defn- draw-background! [area-widget]
  (let [bg-w (cgui-core/create-widget :pos [0 0] :size [(int 257) (int 139)])
        {:keys [bg-area]} (skill-tree-textures)]
    (comp/add-component! bg-w (comp/draw-texture bg-area 0xFFFFFFFF))
    (cgui-core/add-widget! area-widget bg-w)))

(defn- draw-connection-line! [area-widget {:keys [from-x from-y to-x to-y child-learned? m-alpha child-idx]} anim-time]
  (let [lb (line-blend anim-time child-idx)
        dx (- to-x from-x) dy (- to-y from-y)
        norm (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (when (pos? norm)
      (let [ndx (/ dx norm) ndy (/ dy norm)
            line-alpha (* (or m-alpha 0.7) (if child-learned? 1.0 0.4))  ;; upstream: no lineBlend in alpha, only in geometry
            alpha-byte (int (* 255.0 line-alpha))
            color (bit-or (bit-shift-left alpha-byte 24) 0xFFFFFF)
            x0 (+ from-x (* ndx 12.2)) y0 (+ from-y (* ndy 12.2))
            x1 (- to-x (* ndx 12.2)) y1 (- to-y (* ndy 12.2))
            edx (* (- x1 x0) lb)
            edy (* (- y1 y0) lb)
            tex (modid/asset-path "textures" "guis/developer/line.png")
            line-w (cgui-core/create-widget :pos [(int x0) (int y0)] :size [1 1])]
        (comp/add-component! line-w (comp/rotated-line {:tex tex :dx edx :dy edy :line-w 5.5 :color color}))
        (cgui-core/add-widget! area-widget line-w)))))

(defn- draw-skill-node!
  "Render one skill node matching original SkillTree.scala layering.
  Layer order (bottom→top): skill_back → dark outline → icon → shader ring → click-catcher."
  [root area-widget container node anim-time parallax-x parallax-y dev-type node-scale]
  (let [{:keys [x y idx learned can-learn locked? skill-id skill-icon m-alpha]} node
        {:keys [skill-back skill-outline]} (skill-tree-textures)
        ba (back-alpha anim-time idx (or m-alpha 0.7))
        ia (icon-alpha anim-time idx (or m-alpha 0.7))]
    (when (>= ba 0.001)
      (let [;; Scale around TotalSize center (original: glTranslate to TotalSize/2, glScale, translate back)
            ;; screen top-left of the scaled TotalSize block:
            ;;   x + DrawAlign + TotalSize*(1-s)/2 - parallax
            center-x (+ x draw-align (/ (* total-size (- 1.0 node-scale)) 2.0) (- parallax-x))
            center-y (+ y draw-align (/ (* total-size (- 1.0 node-scale)) 2.0) (- parallax-y))
            ;; All positions derived from center-x/y, offsets scaled by node-scale
            back-px (int center-x) back-py (int center-y) back-sz (int (* total-size node-scale))
            ol-px   (int (+ center-x (* prog-align node-scale)))
            ol-py   (int (+ center-y (* prog-align node-scale))) ol-sz (int (* prog-sz node-scale))
            ic-px   (int (+ center-x (* icon-align node-scale)))
            ic-py   (int (+ center-y (* icon-align node-scale))) ic-sz (int (* icon-sz node-scale))
            ;; Colors
            ba-byte (int (* 255.0 ba))
            back-color (bit-or (bit-shift-left ba-byte 24) 0xFFFFFF)
            ol-byte (int (* 255.0 (* ba 0.6)))
            g-byte  (int (* 255.0 0.2))
            ol-color (bit-or (bit-shift-left ol-byte 24)
                             (bit-or (bit-shift-left g-byte 16)
                                     (bit-or (bit-shift-left g-byte 8) g-byte)))]

        ;; 1. skill_back (TotalSize=23, bottom) — white × backAlpha
        (let [bk-w (cgui-core/create-widget :pos [back-px back-py] :size [back-sz back-sz])]
          (comp/add-component! bk-w (comp/draw-texture skill-back back-color))
          (cgui-core/add-widget! area-widget bk-w))

        ;; 2. Dark outline (all nodes) — original draws this for both learned and unlearned
        (let [ol-w (cgui-core/create-widget :pos [ol-px ol-py] :size [ol-sz ol-sz])]
          (comp/add-component! ol-w (comp/draw-texture skill-outline ol-color))
          (cgui-core/add-widget! area-widget ol-w))

        ;; 3. Skill icon — white for learned, gray tint for unlearned (matching original mono shader)
        (when (and skill-icon (>= ia 0.001))
          (let [icon-path (modid/asset-path "textures" (subs skill-icon (count "textures/")))
                ia-byte  (int (* 255.0 ia))
                icon-color (bit-or (bit-shift-left ia-byte 24) (if learned 0xFFFFFF 0x888888))
                ic-w (cgui-core/create-widget :pos [ic-px ic-py] :size [ic-sz ic-sz])]
            (comp/add-component! ic-w (comp/draw-texture icon-path icon-color))
            (cgui-core/add-widget! area-widget ic-w)))

        ;; 4. Shader progress ring (learned only, on top of icon)
        (when learned
          (let [pb (progress-blend anim-time idx)
                mask-path (modid/asset-path "textures" "guis/developer/skill_radial_mask.png")
                ring-path (modid/asset-path "textures"
                            (if (>= (or (:exp node) 0.0) 0.999)
                              "guis/developer/skill_view_outline_glow.png"
                              "guis/developer/skill_view_outline.png"))
                ring-w (cgui-core/create-widget :pos [ol-px ol-py] :size [ol-sz ol-sz])]
            (comp/add-component! ring-w (comp/shader-ring {:texture-0 ring-path
                                                            :texture-1 mask-path
                                                            :progress (float (* pb (or (:exp node) 0.0)))}))
            (cgui-core/add-widget! area-widget ring-w)))

        ;; 5. Transparent click-catcher (topmost) at original WidgetSize position (x,y without DrawAlign)
        ;;    matching original: widget.pos(sx, sy).size(WidgetSize, WidgetSize) → click area = 16×16
        (when (and (or can-learn learned) (not locked?))
          (let [click-px (int (- x parallax-x))
                click-py (int (- y parallax-y))
                click-sz (int (* widget-size node-scale))
                click-w  (cgui-core/create-widget :pos [click-px click-py] :size [click-sz click-sz])]
            (events/on-left-click click-w
              (fn [_] (create-skill-detail-overlay! root container skill-id dev-type)))
            (cgui-core/add-widget! area-widget click-w)))))))

(defn- render-skill-tree-area!
  "Render skill tree nodes in parent_right/area.
  Upstream: SkillTree.scala Common.initialize → area FrameEvent + per-skill widgets."
  [root area-widget container player]
  (cgui-core/clear-widgets! area-widget)
  (try
    (let [now (client-bridge/game-time-ms)                 ;; game-time — pauses with ESC
          uuid-str (when player (uuid/player-uuid player))
          session-id (runtime-hooks/require-player-state-session-id "developer.panel")
          pstate (when uuid-str (store/get-player-state* session-id uuid-str))
          dev-type (current-developer-type container)
          render-data (skill-tree/build-render-data-for-player-state pstate dev-type)
          nodes (:skill-nodes render-data)
          connections (:connections render-data)
          _ (when (nil? @skill-tree-creation-time)
              (reset! skill-tree-creation-time now))
          anim-time (/ (- now @skill-tree-creation-time) 1000.0)
          [mx my] (cn.li.mcmod.client.platform-bridge/get-mouse-pos)
          area-w 257 area-h 139
          ;; Parallax offsets (upstream: clampf(0,1,mouseX/width) - 0.5) * max_du_skills
          parallax-x (* (- (clamp01 (/ mx (max 1.0 (double area-w)))) 0.5) 10.0)
          parallax-y (* (- (clamp01 (/ my (max 1.0 (double area-h)))) 0.5) 10.0)]
      (draw-background! area-widget)
      ;; Connection lines (rendered per-node with depth context in the full-screen
      ;; skill tree; in the panel we draw them before nodes for correct layering)
      (doseq [conn connections]
        (draw-connection-line! area-widget
          (assoc conn
            :from-x (- (:from-x conn) parallax-x)
            :from-y (- (:from-y conn) parallax-y)
            :to-x   (- (:to-x conn) parallax-x)
            :to-y   (- (:to-y conn) parallax-y))
          anim-time))
      ;; Hover detection — matching upstream per-widget StateIdle/StateHover FSM
      ;; Each node independently computes its scale based on whether it's the
      ;; currently hovered node (:hover-idx) or the previously hovered (:prev-idx).
      ;; TransitTime = 0.1s (upstream constant).
      (let [closest-node (when (seq nodes)
                           (apply min-key
                             (fn [n]
                               (let [cx (+ (:x n) (/ widget-size 2) (- parallax-x))
                                     cy (+ (:y n) (/ widget-size 2) (- parallax-y))]
                                 (+ (* (- mx cx) (- mx cx)) (* (- my cy) (- my cy)))))
                             nodes))
            hover-dist (when closest-node
                         (let [cx (+ (:x closest-node) (/ widget-size 2) (- parallax-x))
                               cy (+ (:y closest-node) (/ widget-size 2) (- parallax-y))]
                           (Math/sqrt (+ (* (- mx cx) (- mx cx)) (* (- my cy) (- my cy))))))
            hover-idx (:idx closest-node)
            hover-now? (and closest-node (< (or hover-dist 999) 20))
            prev-idx (:hover-idx @skill-tree-hover)]
        ;; Update hover atom with direction-aware transition (upstream: StateIdle↔StateHover)
        (if hover-now?
          (when (not= hover-idx prev-idx)
            (reset! skill-tree-hover {:hover-idx hover-idx :prev-idx prev-idx :start now}))
          (when prev-idx
            (reset! skill-tree-hover {:hover-idx nil :prev-idx prev-idx :start now})))
        (let [hover-idx (:hover-idx @skill-tree-hover)
              unhover-idx (:prev-idx @skill-tree-hover)
              hover-start (:start @skill-tree-hover)
              hover-transit (clamp01 (/ (- now hover-start) 100.0))]   ;; 100ms = 0.1s TransitTime
          (doseq [node nodes]
            (when (and (:skill-id node) (:x node) (:y node))
              (let [node-hovered? (= (:idx node) hover-idx)
                    node-unhovering? (and (nil? hover-idx) (= (:idx node) unhover-idx))
                    node-scale (cond
                                 node-hovered?     (lerp 1.0 1.2 hover-transit)   ;; StateHover: 1.0→1.2
                                 node-unhovering?  (lerp 1.2 1.0 hover-transit)   ;; StateIdle:  1.2→1.0
                                 :else             1.0)]
                (draw-skill-node! root area-widget container node anim-time parallax-x parallax-y dev-type node-scale)))))))
    (catch Exception e
      (log/error "Skill tree render failed:" (ex-message e))
      (log/stacktrace "Skill tree render failed" e))))

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
                (reset! skill-tree-creation-time nil)
                (when (not= mode :skill-tree) (cgui-core/clear-widgets! right-area))
                (case mode
                  :console (render-console-area! root right-area container pl :learn)
                  :reset-console (render-console-area! root right-area container pl :reset)
                  :skill-tree nil
                  nil))
              (when (= mode :skill-tree)
                (render-skill-tree-area! root right-area container pl)))))))

    root))


