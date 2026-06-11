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
        lvl (long (:level ad 1))
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
                          (developer/gte? developer-type (developer/min-for-level (inc lvl))))
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

(defn- create-black-cover
  "Create a semi-transparent black overlay widget that covers its parent.
  Returns the cover widget."
  [parent]
  (let [[pw ph] (cgui-core/get-size parent)
        cover (cgui-core/create-widget :pos [0 0] :size [pw ph])
        _ (comp/add-component! cover
            (comp/draw-texture nil 0x80000000))]  ;; black 50% alpha
    cover))

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
  "Create overlay showing skill details + learn button."
  [root container skill-id _developer-type]
  (let [cover (create-black-cover root)
        close-fn #(cgui-core/remove-widget! root cover)
        panel (create-centered-panel cover 200 140)
        bg (cgui-core/create-widget :pos [0 0] :size [200 140])
        skill-spec (skill/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")]
    ;; Close overlay when clicking outside the panel (not on panel children)
    (events/on-left-click cover
      (fn [evt]
        (let [mx (int (:x evt 0)) my (int (:y evt 0))
              [px py] (cgui-core/get-pos panel)
              [pw ph] (cgui-core/get-size panel)]
          (when (or (< mx px) (> mx (+ px pw))
                    (< my py) (> my (+ py ph)))
            (close-fn)))))
    ;; Background
    (comp/add-component! bg (comp/draw-texture nil 0xC0202020))
    (cgui-core/add-widget! panel bg)
    ;; Title
    (let [title (cgui-core/create-widget :pos [10 8] :size [180 14])
          tb (comp/text-box :text (str "Learn: " skill-name) :font :ac-bold :font-size 12 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb)
      (cgui-core/add-widget! panel title))
    ;; Learn button
    (let [btn (cgui-core/create-widget :pos [50 90] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Learn" :font :ac-normal :font-size 9 :align :center :color 0xFFFFFFFF)]
      (comp/add-component! btn btn-bg)
      (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (req-start-development! container :learn-skill {:skill-id (name skill-id)})
          (close-fn)))
      (cgui-core/add-widget! panel btn))
    ;; Progress bar
    (let [prog-w (cgui-core/create-widget :pos [10 115] :size [180 8])
          prog-bar (comp/progress-bar :direction :right :progress 0.0
                     :color-full 0xFF25c4ff :color-empty 0x40404040)]
      (comp/add-component! prog-w prog-bar)
      (cgui-core/set-visible! prog-w false)
      (cgui-core/add-widget! panel prog-w)
      (let [prog-comp (comp/get-widget-component prog-w :progressbar)]
        (events/on-frame cover
          (fn [_]
            (let [is-dev (boolean @(:is-developing container))
                  dev-prog (double (or @(:development-progress container) 0.0))]
              (cgui-core/set-visible! prog-w is-dev)
              (when is-dev
                (comp/set-progress! prog-comp (min 1.0 dev-prog))))))))
    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Level-up overlay
;; ============================================================================

(defn- create-level-up-overlay!
  "Create overlay for ability level-up."
  [root container developer-type]
  (let [cover (create-black-cover root)
        close-fn #(cgui-core/remove-widget! root cover)
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
    ;; Progress bar
    (let [prog-w (cgui-core/create-widget :pos [10 100] :size [160 8])
          prog-bar (comp/progress-bar :direction :right :progress 0.0
                     :color-full 0xFF25c4ff :color-empty 0x40404040)]
      (comp/add-component! prog-w prog-bar)
      (cgui-core/set-visible! prog-w false)
      (cgui-core/add-widget! panel prog-w)
      (let [prog-comp (comp/get-widget-component prog-w :progressbar)]
        (events/on-frame cover
          (fn [_]
            (let [is-dev (boolean @(:is-developing container))
                  dev-prog (double (or @(:development-progress container) 0.0))]
              (cgui-core/set-visible! prog-w is-dev)
              (when is-dev
                (comp/set-progress! prog-comp (min 1.0 dev-prog))))))))
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
  "Render skill tree nodes in parent_right/area."
  [root area-widget container player]
  (cgui-core/clear-widgets! area-widget)
  (try
    (let [uuid-str (when player (uuid/player-uuid player))
          session-id (runtime-hooks/require-player-state-session-id "developer.panel")
          pstate (when uuid-str (store/get-player-state* session-id uuid-str))
          dev-type (current-developer-type container)
          render-data (skill-tree/build-render-data-for-player-state pstate dev-type)
          nodes (:skill-nodes render-data)]
      (when (seq nodes)
        (doseq [{:keys [x y learned can-learn skill-id skill-name]} nodes
                :when (and skill-id x y)]
          (let [node-x (int (+ x 10))
                node-y (int (+ y 10))
                size 16
                color (cond learned 0xCC66CC66 can-learn 0xCC6699FF :else 0xAA444444)
                node-w (cgui-core/create-widget :pos [node-x node-y] :size [size size])
                fill (comp/draw-texture nil color)]
            (comp/add-component! node-w fill)
            ;; Label
            (let [label (cgui-core/create-widget :pos [(+ node-x size 4) (+ node-y 2)] :size [120 12])
                  tb (comp/text-box :text (str skill-name) :font :ac-normal :font-size 9 :align :left :color 0xFFFFFFFF)]
              (comp/add-component! label tb)
              (cgui-core/add-widget! area-widget label))
            ;; Click handler
            (when can-learn
              (events/on-left-click node-w
                (fn [_] (create-skill-detail-overlay! root container skill-id dev-type))))
            (cgui-core/add-widget! area-widget node-w)))))
    (catch Exception e
      (log/error "Skill tree render failed:" (ex-message e)))))

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
        [_panel state-a] (dev-console/create-console area-widget
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

    root)))


