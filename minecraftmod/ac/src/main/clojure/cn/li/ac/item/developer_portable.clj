(ns cn.li.ac.item.developer-portable
  "Portable Developer item — opens the classic AcademyCraft developer UI as a
  standalone CGUI screen (no block container).

  Reuses block/developer/panel.clj and block/developer/console.clj for the full
  CGUI widget tree while replacing block-specific development requests with
  instant API calls (api/req-learn-skill! / api/req-level-up!)."
  (:require [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.skill-tree :as skill-tree]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.domain.developer :as developer]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.config :as cfg]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.block.developer.console :as dev-console]
            [cn.li.ac.block.developer.panel :as dev-panel]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.item.special-items :as special-items]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str])
  ;; Minecraft classes accessed via platform abstractions (cn.li.mcmod.platform.*);
  ;; no direct :import needed — avoids compile-time classpath coupling to Minecraft jars.
  )

;; ============================================================================
;; Portable Container — mimics block container atoms for CGUI panels
;; ============================================================================

(def ^:private portable-max-energy 10000.0)
(def ^:private portable-bandwidth 0.3)
(def ^:private session-ns-prefix "developer.portable")

(defn- get-player-held-stack
  "Get the player's main-hand ItemStack via platform abstraction."
  [player]
  (when player
    (entity/player-get-main-hand-item-stack player)))

(defn- current-energy-from-held-item
  "Read current energy from the player's held developer_portable item."
  [player]
  (let [stack (get-player-held-stack player)]
    (if (and stack (energy/is-energy-item-supported? stack))
      (double (energy/get-item-energy stack))
      0.0)))

(defn- update-held-item-energy!
  "Sync the container energy atom from the held item's current energy."
  [player energy-atom]
  (let [v (current-energy-from-held-item player)]
    (reset! energy-atom v)))

(defn make-portable-container
  "Create a portable container map that satisfies the CGUI developer panel's
  container interface without needing a tile entity.

  Atom fields read by panel.clj:
  - :energy, :max-energy, :tier, :is-developing, :development-progress
  - :user-uuid, :user-name"
  [player]
  (let [player-uuid-str (or (entity/player-get-uuid player) "")
        player-name-str (or (entity/player-get-name player) "")]
    {:energy (atom (current-energy-from-held-item player))
     :max-energy (atom portable-max-energy)
     :tier (atom :portable)
     :is-developing (atom false)
     :development-progress (atom 0.0)
     :user-uuid (atom player-uuid-str)
     :user-name (atom player-name-str)
     :player player
     :tile-entity nil ;; portable has no tile
     :container-type :portable-developer}))

;; ============================================================================
;; Overlay helpers — portable mode uses instant API calls (no timed dev)
;; ============================================================================

(defn- create-black-cover
  [parent]
  (let [[pw ph] (cgui-core/get-size parent)
        cover (cgui-core/create-widget :pos [0 0] :size [pw ph])
        _ (comp/add-component! cover (comp/draw-texture nil 0x80000000))]
    cover))

(defn- create-centered-panel
  [cover panel-width panel-height]
  (let [[cw ch] (cgui-core/get-size cover)
        cx (int (/ (- cw panel-width) 2))
        cy (int (/ (- ch panel-height) 2))
        panel (cgui-core/create-widget :pos [cx cy] :size [panel-width panel-height])]
    (cgui-core/add-widget! cover panel)
    panel))

(defn- create-portable-skill-detail-overlay!
  "Overlay showing skill details + learn button. Uses api/req-learn-skill!
  for instant learning (portable developer has no timed development)."
  [root owner skill-id]
  (let [cover (create-black-cover root)
        close-fn #(cgui-core/remove-widget! root cover)
        panel (create-centered-panel cover 200 140)
        bg (cgui-core/create-widget :pos [0 0] :size [200 140])
        skill-spec (skill-registry/get-skill skill-id)
        skill-name (or (:name skill-spec) (name skill-id) "Unknown")]
    ;; Close when clicking outside panel
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
    (let [title (cgui-core/create-widget :pos [10 8] :size [180 14])
          tb (comp/text-box :text (str "Learn: " skill-name) :font :ac-bold :font-size 12
                            :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb)
      (cgui-core/add-widget! panel title))
    ;; Learn button
    (let [btn (cgui-core/create-widget :pos [50 90] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Learn" :font :ac-normal :font-size 9
                                :align :center :color 0xFFFFFFFF)]
      (comp/add-component! btn btn-bg)
      (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (api/req-learn-skill! owner skill-id)
          (close-fn)))
      (cgui-core/add-widget! panel btn))
    (cgui-core/add-widget! root cover)))

(defn- create-portable-level-up-overlay!
  "Overlay for ability level-up. Uses api/req-level-up! for instant level-up."
  [root owner]
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
          tb (comp/text-box :text "Level Up" :font :ac-bold :font-size 12
                            :align :center :color 0xFFFFFFFF)]
      (comp/add-component! title tb)
      (cgui-core/add-widget! panel title))
    ;; Description
    (let [desc (cgui-core/create-widget :pos [10 28] :size [160 24])
          tb (comp/text-box :text "Level up your ability.\nPortable Developer"
                            :font :ac-normal :font-size 9 :align :center :color 0xFFCCCCCC)]
      (comp/add-component! desc tb)
      (cgui-core/add-widget! panel desc))
    ;; Confirm button
    (let [btn (cgui-core/create-widget :pos [40 72] :size [100 20])
          btn-bg (comp/draw-texture nil 0xFF226622)
          btn-tb (comp/text-box :text "Confirm" :font :ac-normal :font-size 9
                                :align :center :color 0xFFFFFFFF)]
      (comp/add-component! btn btn-bg)
      (comp/add-component! btn btn-tb)
      (events/on-left-click btn
        (fn [_]
          (api/req-level-up! owner)
          (close-fn)))
      (cgui-core/add-widget! panel btn))
    (cgui-core/add-widget! root cover)))

;; ============================================================================
;; Widget helpers (mirror panel.clj)
;; ============================================================================

(defn- set-text-path! [root path text]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [tb (comp/get-widget-component w :textbox)]
      (comp/set-text! tb (str text)))))

(defn- set-progress-path! [root path ^double v]
  (when-let [w (cgui-core/find-widget root path)]
    (when-let [pb (comp/get-widget-component w :progressbar)]
      (comp/set-progress! pb v))))

(defn- set-drawtexture-path! [root path texture-path]
  (when (string? texture-path)
    (when-let [w (cgui-core/find-widget root path)]
      (when-let [dt (comp/get-drawtexture-component w)]
        (comp/set-texture! dt texture-path)))))

(defn- set-visible-path! [root path visible?]
  (when-let [w (cgui-core/find-widget root path)]
    (cgui-core/set-visible! w visible?)))

(defn- texture-path-from-category-icon [icon-str]
  (when (string? icon-str)
    (if (str/starts-with? icon-str "textures/")
      (modid/asset-path "textures" (subs icon-str (count "textures/")))
      (modid/asset-path "textures" icon-str))))

(defn- default-ability-icon-path []
  (modid/asset-path "textures" "guis/icons/icon_nocategory.png"))

;; ============================================================================
;; UI Model builder — left panel data
;; ============================================================================

(defn- current-ui-model
  "Build UI model for left-panel bindings from portable container."
  [container player]
  (let [energy (double (or @(:energy container) 0.0))
        max-energy (max 1.0 (double (or @(:max-energy container) 1.0)))
        uuid-str (when player (entity/player-get-uuid player))
        pstate (when uuid-str
                 (store/get-player-state*
                   (runtime-hooks/require-player-state-session-id session-ns-prefix)
                   uuid-str))
        ad (:ability-data pstate)
        cat-id (:category-id ad)
        cat (when cat-id (category/get-category cat-id))
        has-category? (boolean cat)
        lvl (long (:level ad 1))
        level-prog (double (:level-progress ad 0.0))
        skills-at-level (when cat-id (skill-query/get-controllable-skills-at-level cat-id lvl))
        cat-rate (when cat-id (category/get-prog-incr-rate cat-id))
        thresh (when (and cat-id (not (>= lvl 5)))
                 (learning-rules/level-up-threshold ad
                   skills-at-level cat-rate (cfg/prog-incr-rate)))
        cat-prog01 (if has-category?
                     (if (and thresh (pos? thresh))
                       (bal/clamp01 (/ level-prog thresh))
                       (if (>= lvl 5) 1.0 0.0))
                     0.0)
        can-upgrade? (and has-category? (< lvl 5)
                          (developer/gte? :portable (developer/min-for-level (inc lvl))))
        ability-name (if has-category? (i18n/translate (:name-key cat)) "N/A")
        icon-path (if has-category?
                    (or (some-> cat :icon texture-path-from-category-icon)
                        (default-ability-icon-path))
                    (default-ability-icon-path))
        exp-label (if has-category?
                    (if (>= lvl 5) "MAX"
                        (if thresh (format "EXP %.0f%%" (* 100.0 cat-prog01)) "EXP 0%"))
                    "EXP 0%")
        level-label (format "Level %d" lvl)]
    {:has-category? has-category?
     :can-upgrade? can-upgrade?
     :ability-name ability-name
     :icon-path icon-path
     :exp-label exp-label
     :level-label level-label
     :cat-prog01 cat-prog01
     :sync-rate 0.3  ;; PORTABLE sync rate — fixed device property
     :power01 (bal/clamp01 (/ energy max-energy))}))

;; ============================================================================
;; Right panel mode dispatch
;; ============================================================================

(defn- player-holding-magnetic-coil? [player]
  (and player
       (satisfies? entity/IEntityOps player)
       (= special-items/magnetic-coil-item-id
          (entity/player-get-main-hand-item-id player))))

(defn- right-panel-mode
  [player]
  (let [uuid-str (when player (entity/player-get-uuid player))
        pstate (when uuid-str
                 (store/get-player-state*
                   (runtime-hooks/require-player-state-session-id session-ns-prefix)
                   uuid-str))
        ad (:ability-data pstate)
        has-cat? (boolean (:category-id ad))
        holding-coil? (player-holding-magnetic-coil? player)]
    (cond
      (and has-cat? holding-coil?) :reset-console
      (not has-cat?) :console
      :else :skill-tree)))

;; ============================================================================
;; Right panel renderers
;; ============================================================================

(defn- render-skill-tree-area!
  "Render skill tree nodes in parent_right/area for portable developer."
  [root area-widget _container player owner]
  (cgui-core/clear-widgets! area-widget)
  (try
    (let [uuid-str (when player (entity/player-get-uuid player))
          session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)
          pstate (when uuid-str (store/get-player-state* session-id uuid-str))
          dev-type :portable
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
            (let [label (cgui-core/create-widget :pos [(+ node-x size 4) (+ node-y 2)]
                                                 :size [120 12])
                  tb (comp/text-box :text (str skill-name) :font :ac-normal :font-size 9
                                    :align :left :color 0xFFFFFFFF)]
              (comp/add-component! label tb)
              (cgui-core/add-widget! area-widget label))
            (when can-learn
              (events/on-left-click node-w
                (fn [_] (create-portable-skill-detail-overlay! root owner skill-id))))
            (cgui-core/add-widget! area-widget node-w)))))
    (catch Exception e
      (log/error "Portable skill tree render failed:" (ex-message e)))))

(defn- render-console-area!
  "Render text console in parent_right/area for portable developer."
  [root area-widget container player mode owner]
  (cgui-core/clear-widgets! area-widget)
  (let [start-action (if (= :reset mode) :reset :level-up)
        state-ref (atom nil)
        on-start-dev (fn []
                       (case start-action
                         :level-up (api/req-level-up! owner)
                         :reset (api/req-set-activated! owner false)
                         nil))
        [_panel state-a] (dev-console/create-console area-widget
                           {:mode mode
                            :container container
                            :player-name (or @(:user-name container) "Player")
                            :focus-root root
                            :on-start-development on-start-dev})]
    (reset! state-ref state-a)))

;; ============================================================================
;; Main CGUI screen builder
;; ============================================================================

(defn- attach-portable-bindings!
  "Attach event handlers to the classic page_developer.xml widgets for portable mode.

  Mirrors panel.clj/attach-classic-developer-bindings! but:
  - Uses portable overlays (instant API calls instead of timed dev)
  - Hides wireless button (portable has no wireless link)
  - Reads energy from held item on each frame tick"
  [root container player owner]
  ;; Hide wireless button — portable developer has no wireless
  (set-visible-path! root "parent_left/panel_machine/button_wireless" false)
  (set-visible-path! root "parent_left/panel_machine/text_wireless" false)

  ;; btn_upgrade → portable level-up overlay
  (when-let [upgrade-btn (cgui-core/find-widget root "parent_left/panel_ability/btn_upgrade")]
    (events/on-left-click upgrade-btn
      (fn [_] (create-portable-level-up-overlay! root owner))))

  ;; Frame handler — updates left panel + right panel mode dispatch
  (let [right-area (cgui-core/find-widget root "parent_right/area")
        last-mode (atom nil)]
    (events/on-frame root
      (fn [_]
        ;; Sync energy from held item to container atom each frame
        (update-held-item-energy! player (:energy container))

        ;; Left panel updates
        (let [{:keys [ability-name icon-path exp-label level-label
                      cat-prog01 power01 sync-rate can-upgrade?]}
              (current-ui-model container player)]
          (set-text-path! root "parent_left/panel_ability/text_abilityname" ability-name)
          (set-drawtexture-path! root "parent_left/panel_ability/logo_ability" icon-path)
          (set-text-path! root "parent_left/panel_ability/text_exp" exp-label)
          (set-text-path! root "parent_left/panel_ability/text_level" level-label)
          (set-progress-path! root "parent_left/panel_ability/logo_progress" cat-prog01)
          (set-progress-path! root "parent_left/panel_machine/progress_power" power01)
          (set-progress-path! root "parent_left/panel_machine/progress_syncrate" (double sync-rate))
          (set-visible-path! root "parent_left/panel_ability/btn_upgrade" can-upgrade?))

        ;; Right panel mode dispatch
        (when right-area
          (let [mode (right-panel-mode player)]
            (when (not= mode @last-mode)
              (reset! last-mode mode)
              (case mode
                :console (render-console-area! root right-area container player :learn owner)
                :reset-console (render-console-area! root right-area container player :reset owner)
                :skill-tree (render-skill-tree-area! root right-area container player owner)))))))))

(defn create-portable-developer-gui
  "Build the CGUI widget tree for the portable developer screen.
  Returns the root widget ready to be hosted on a plain Screen."
  [player]
  (try
    (let [container (make-portable-container player)
          root (dev-panel/load-classic-developer-page)
          _ (tech-ui/init-cgui-root-metadata! root)
          session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)
          owner (read-model/canonical-client-owner
                  {:client-session-id session-id
                   :player-uuid (entity/player-get-uuid player)}
                  :skill-tree)]
      ;; Ensure player state exists so the skill tree can read it
      (let [owner-key (read-model/owner-key owner :skill-tree)]
        (read-model/ensure-player-state! owner-key))
      ;; Attach bindings (left panel, right panel, overlays)
      (attach-portable-bindings! root container player owner)
      (log/info "Created Portable Developer CGUI screen")
      root)
    (catch Exception e
      (log/error "Portable developer GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  "Build the CGUI screen data map for the portable developer.
  Returns {:type :cgui-screen, :cgui root-widget, :session-id str}."
  [player]
  (let [root (create-portable-developer-gui player)
        session-id (runtime-hooks/require-player-state-session-id session-ns-prefix)]
    {:type :cgui-screen
     :cgui root
     :session-id session-id}))
