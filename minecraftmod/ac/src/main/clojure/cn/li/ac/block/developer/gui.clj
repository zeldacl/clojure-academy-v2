(ns cn.li.ac.block.developer.gui
  "Ability Developer GUI: single-screen classic page_developer.xml + overlays.

  - No TechUI tabs (original AcademyCraft is single-screen)
  - No visible inventory slots (original has 2 internal TE slots for automation,
    not user-visible in GUI)
  - Wireless panel: black-cover overlay popup triggered by wireless button
  - Info area: energy histogram + status properties (right sidebar)
  - Right panel: mode dispatch via panel.clj (skill-tree / console / reset-console)"
  (:require [clojure.string :as str]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.block.developer.schema :as dev-schema]
            [cn.li.ac.block.developer.logic :as dev-logic]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.developer.panel :as dev-panel]
            [cn.li.ac.block.developer.session :as dev-session]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]))

(def ^:private developer-gui-id :developer-gui)

;; Classic page_developer.xml is 400×187
(def ^:private developer-main-w 400.0)
(def ^:private developer-main-h 187.0)
(def ^:private info-area-width 100.0)

;; Cover overlay animation constants — matching original AcademyCraft Cover component
;; SkillTree.scala: glColor4d(0, 0, 0, alpha * 0.7) with fade duration 0.2s
(def ^:private cover-max-alpha 0.7)
(def ^:private cover-fade-duration 0.2)

(def gui-width (long (+ developer-main-w 7.0 info-area-width)))
(def gui-height (long developer-main-h))

(def ^:private developer-sync
  (gui-sync/schema-sync-fns dev-schema/unified-developer-schema))

;; ============================================================================
;; Container — internal inventory (2 slots, not user-visible) + state sync
;; ============================================================================

(defn create-container [tile player]
  (let [state (or (common/get-tile-state tile) {})]
    (gui-sync/create-schema-container dev-schema/unified-developer-schema
                                      tile player :developer
                                      {:gui-id (gui-manifest/gui-id :developer)
                                       :state state})))

(defn get-slot-count [_container]
  0)  ;; No visible slots — inventory is internal-only for automation

(defn can-place-item? [_container _slot-index _item-stack]
  true)

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack
                            dev-logic/dev-default-state
                            (fn [state]
                              (let [v (vec (take 2 (concat (vec (:inventory state [])) (repeat nil))))]
                                (assoc state :inventory v))))
  (when-let [tile (:tile-entity container)]
    (try (platform-be/set-changed! tile) (catch Exception _ nil)))
  nil)

(defn slot-changed! [_container _slot-index] nil)

(defn still-valid? [container player]
  (and (common/still-valid? container player)
       (let [tile (:tile-entity container)
             st (or (common/get-tile-state tile) {})
             pid (uuid/player-uuid player)
             holder (str (:user-uuid st ""))]
         (or (str/blank? holder) (= holder pid)))))

(def server-menu-sync! (:server-menu-sync! developer-sync))

(defn handle-button-click! [_container _button-id _player] nil)

(defn on-close [container]
  ((:on-close developer-sync) container)
  (when-let [tile (:tile-entity container)]
    (when-let [pl (:player container)]
      (let [lvl (entity/player-get-level pl)]
        (when (and lvl (not (world/world-is-client-side* lvl)))
          (try
            (machine-runtime/commit-transform! tile dev-logic/dev-default-state
              #(-> % (assoc :user-uuid "" :user-name "") dev-session/clear-session))
            (catch Exception e
              (log/debug "Developer on-close tile update:" (ex-message e)))))))))

;; ============================================================================
;; Wireless overlay
;; ============================================================================

(defn- time-secs []
  (/ (double (System/currentTimeMillis)) 1000.0))

(defn- create-wireless-overlay!
  "Create a black-cover overlay containing the wireless link panel.
  Matches original AcademyCraft Cover component (SkillTree.scala:897-927):
  - Cover: full-screen black rect, fade-in alpha 0 → 0.7 over 0.2s
  - Panel: centered via CENTER/CENTER alignment (original .centered())
  - Click cover / ESC → fade-out alpha 0.7 → 0 over 0.2s
  - Fade-out complete → remove overlay + on-close callback

  Original flow:
    val wirelessPage = WirelessPage.userPage(tile).window.centered()
    val cover = blackCover(gui)
    cover :+ wirelessPage
    cover.listens[LeftClickEvent](() => cover.component[Cover].end())
    cover.listens[CloseEvent](() => gui.postEvent(new RebuildEvent))"
  [root container & [{:keys [on-close]}]]
  (try
    (let [[rw rh] (cgui-core/get-size root)
          ;; Cover widget: full-screen, initially transparent
          cover (cgui-core/create-widget :pos [0 0] :size [rw rh])
          _ (comp/add-component! cover (comp/draw-texture nil (comp/mono-blend 0.0 0.0)))
          dt-comp (comp/get-drawtexture-component cover)
          ;; Animation state
          state (atom {:start-time (time-secs)
                       :ended? false
                       :end-start-time 0.0
                       :close-called? false})
          ;; Wireless panel (original: WirelessPage.userPage(tile).window)
          node-icon (modid/asset-path "textures" "guis/icons/icon_node.png")
          window (wireless-tab/create-wireless-panel
                   {:role :receiver
                    :container container
                    :tab-logo-path node-icon
                    :connected-row-logo-path node-icon
                    :defer-initial-rebuild? false})
          ;; Original: wirelessPage.window.centered() → CENTER/CENTER
          _ (cgui-core/set-w-align! window :center)
          _ (cgui-core/set-h-align! window :center)

          ;; end-cover! — matching Cover.end() in original.
          end-cover! (fn []
                       (let [{:keys [ended? close-called?]} @state]
                         (when (and (not ended?) (not close-called?))
                           (swap! state assoc :ended? true :end-start-time (time-secs)))))

          ;; on-close guarded — matching CloseEvent → RebuildEvent in original
          call-on-close! (fn []
                           (when-not (:close-called? @state)
                             (swap! state assoc :close-called? true)
                             ;; Matching original: gui.removeWidget("link_page")
                             (swap! (:metadata root) dissoc :developer-cover-end-fn)
                             (when on-close (on-close))))]

      ;; Register end-cover! so screen-level :key-hook can find it.
      ;; Matching original: gui.addWidget("link_page", cover)
      (swap! (:metadata root) assoc :developer-cover-end-fn end-cover!)

      ;; Frame handler: fade animation + resize + completion check
      ;; Original: Cover listens[FrameEvent] → glColor4d(0,0,0,alpha*0.7) + HudUtils.colorRect
      (events/on-frame cover
        (fn [_]
          (let [{:keys [start-time ended? end-start-time]} @state
                t (time-secs)
                elapsed (- t (if ended? end-start-time start-time))
                src (min 1.0 (/ (max 0.0 elapsed) cover-fade-duration))
                alpha (if ended?
                        (* cover-max-alpha (- 1.0 src))   ;; fade-out: 0.7 → 0
                        (* cover-max-alpha src))           ;; fade-in: 0 → 0.7
                a (max 0.0 alpha)]
            ;; Update draw-texture color each frame (matching glColor4d)
            (swap! (:state dt-comp) assoc :color (comp/mono-blend 0.0 a))
            ;; Match root size each frame (original: widget.transform.width = gui.getWidth)
            (let [[pw ph] (cgui-core/get-size root)]
              (cgui-core/set-size! cover pw ph))
            ;; Fade-out complete → dispose (original: if(ended && alpha==0) widget.dispose())
            (when (and ended? (<= a 0.0))
              (cgui-core/remove-widget! root cover)
              (call-on-close!)))))

      ;; Add window to cover, cover to root (original: cover :+ wirelessPage)
      (cgui-core/add-widget! cover window)
      (cgui-core/add-widget! root cover)

      ;; Click cover → fade-out (original: cover.listens[LeftClickEvent](() => cover.component[Cover].end()))
      (events/on-left-click cover (fn [_] (end-cover!)))

      cover)
    (catch Exception e
      (log/error "Wireless overlay:" (ex-message e))
      nil)))

;; ============================================================================
;; GUI assembly — single screen
;; ============================================================================

(defn create-developer-gui
  "Create the single-screen developer GUI: page_developer.xml root + info area.
  No tabs, no visible inventory slots, no embedded wireless."
  [container _player & [opts]]
  (try
    (let [container (cond-> container
                      (:menu opts) (assoc :minecraft-container (:menu opts)))
          classic-root (dev-panel/load-classic-developer-page)
          _ (tech-ui/init-cgui-root-metadata! classic-root)
          ;; Attach left panel bindings + right panel mode dispatch
              _ (dev-panel/attach-classic-developer-bindings!
              classic-root container
              {:on-wireless-click
               (fn []
                 (create-wireless-overlay! classic-root container
                   {:on-close #(dev-panel/refresh-linked-node-label! classic-root container)}))})
          ;; Info area (right sidebar)
          info-area (tech-ui/create-info-area)
          max-e (fn [] (max 1.0 (double @(:max-energy container))))
          y0 (tech-ui/add-histogram
               info-area
               [(tech-ui/hist-buffer (fn [] (double @(:energy container))) (max-e))]
               0)
          y1 (tech-ui/add-sepline info-area "Developer" y0)
          y2 (tech-ui/add-property info-area "tier" (fn [] (str @(:tier container))) y1)
          y3 (tech-ui/add-property info-area "structure_ok" (fn [] (str @(:structure-valid container))) y2)
          _y4 (tech-ui/add-property info-area "developing" (fn [] (str @(:is-developing container))) y3)]
      ;; Position info area to the right of the classic page
      (cgui-core/set-position! info-area (+ developer-main-w 7.0) 5.0)
      (tech-ui/reset-info-area! info-area)
      (cgui-core/add-widget! classic-root info-area)
      (log/info "Created Ability Developer GUI (classic single-screen)")
      (if (:menu opts)
        {:root classic-root}
        classic-root))
    (catch Exception e
      (log/error "Developer GUI:" (ex-message e))
      (throw e))))

(defn create-screen
  "Wrap the developer GUI as a CGuiScreenContainer.
  Adds :key-hook matching original AcademyCraft TreeScreen.keyTyped:
    override def keyTyped(ch, key) =
      if (key == KEY_ESCAPE) Option(gui.getWidget(\"link_page\")).map(_.component[Cover].end())
      else super.keyTyped(ch, key)

  Uses overlay stack: ESC pops the most recent overlay (LIFO), matching
  original behavior where each overlay registers its own Cover.end()."
  [container minecraft-container player]
  (let [gui (create-developer-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui-screen/create-cgui-screen-container root minecraft-container)
        ;; Per-screen ESC hook — overlay stack (LIFO), matching original Cover pattern.
        ;; Also supports legacy :developer-cover-end-fn for wireless overlay.
        key-hook (fn [key-code _scan-code _modifiers]
                   (when (= 256 key-code)  ;; GLFW_KEY_ESCAPE
                     ;; Try overlay stack first (LIFO)
                     (let [stack (:cover-end-fns @(:metadata root))
                           legacy-fn (:developer-cover-end-fn @(:metadata root))]
                       (if-let [end-fns (seq stack)]
                         (let [top-fn (peek end-fns)]
                           (swap! (:metadata root) assoc :cover-end-fns (pop end-fns))
                           (when top-fn (top-fn))
                           true)
                         (when legacy-fn
                           (legacy-fn)
                           true))))))]
    (-> base
        ;; Original AcademyCraft: CGuiScreen (full-screen GuiScreen).
        ;; imageWidth=0 → guiLeft=screenWidth/2; XML root CENTER/CENTER → centered.
        (assoc :image-width 0
               :image-height 0
               :current-tab-atom (atom :developer)
               :key-hook key-hook))))

;; ============================================================================
;; Registration
;; ============================================================================

(defn- developer-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (= :developer (:container-type container))))

(def ^:private ^:dynamic *developer-gui-installed?* false)
(def ^:private developer-gui-guard-lock (Object.))

(defn init-developer-gui!
  []
  (when-not (var-get #'*developer-gui-installed?*)
    (locking developer-gui-guard-lock
      (when-not (var-get #'*developer-gui-installed?*)
        ;; Register GUI — no visible slot schema (internal inventory only)
        (gui-reg/register-block-gui!
          (gui-manifest/gui-name :developer)
          (merge (gui-manifest/gui-registration :developer)
                 {:container-predicate developer-container?
                  :container-fn create-container
                  :screen-fn create-screen
                  :server-menu-sync-fn server-menu-sync!
                  :validate-fn still-valid?
                  :close-fn on-close
                  :button-click-fn handle-button-click!
                  :slot-count-fn get-slot-count
                  :slot-get-fn get-slot-item
                  :slot-set-fn set-slot-item!
                  :slot-can-place-fn can-place-item?
                  :slot-changed-fn slot-changed!}))
        (alter-var-root #'*developer-gui-installed?* (constantly true))
        (log/info "Ability Developer GUI registered (single-screen, gui-id 13)")))))
