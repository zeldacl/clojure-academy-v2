(ns cn.li.ac.terminal.client.shell
  "CLIENT-ONLY: terminal shell GUI, RPC, and app grid.

  Matching original AcademyCraft TerminalUI:
  - 3D perspective effect via mouse-driven root transform
  - Continuous scroll via mouseY edge detection
  - Selection cursor with highlight
  - App grid with staggered fade-in animation
  - Time display + app count + page indicator"
  (:require [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.terminal.client.apps :as client-apps]
            [cn.li.ac.terminal.client.install-effect :as install-effect]
            [cn.li.ac.terminal.client.runtime :as runtime]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard terminal-ui-hooks-installed?)

;; Grid config matching original AcademyCraft TerminalUI:
;; START_X=65, START_Y=155, STEP_X=180, STEP_Y=180
(def ^:private grid-config
  {:columns 3
   :rows 3
   :col-x [65 245 425]
   :row-y [155 335 515]
   :app-width 151
   :app-height 151})

;; Original TerminalUI constants
(def ^:private max-mx 605.0)
(def ^:private max-my 740.0)
(def ^:private balance-speed 3000.0)  ;; pixel/s
(def ^:private sensitivity 0.7)
(def ^:private scroll-boundary 40.0)  ;; px from edge to trigger scroll

(def ^:private apps-per-page
  (* (:columns grid-config) (:rows grid-config)))

;; Texture paths matching original
(def ^:private tex-app-back (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
(def ^:private tex-app-back-hdr (modid/asset-path "textures" "guis/data_terminal/app_back_highlight.png"))
(def ^:private tex-cursor (modid/asset-path "textures" "guis/data_terminal/cursor.png"))

(defn- grid-position [index]
  (let [row (quot index (:columns grid-config))
        col (rem index (:columns grid-config))]
    [(get (:col-x grid-config) col)
     (get (:row-y grid-config) row)]))

(defn- page-count [apps]
  (max 1 (int (Math/ceil (/ (double (count apps)) (double apps-per-page))))))

(defn- clamp-page [apps page]
  (let [max-page (dec (page-count apps))]
    (-> (int (or page 0)) (max 0) (min max-page))))

(defn- set-arrow-alpha! [root-widget path alpha]
  (when-let [w (cgui-core/find-widget root-widget path)]
    (when-let [dt (comp/get-drawtexture-component w)]
      (let [a (int (Math/round (* 255.0 (double alpha))))
            color (unchecked-int (bit-or (bit-shift-left (bit-and a 0xFF) 24) 0x00FFFFFF))]
        (swap! (:state dt) assoc :color color)))))

(defn- update-arrow-state! [root-widget apps page]
  (let [max-page (dec (page-count apps))]
    (set-arrow-alpha! root-widget "arrow_up" (if (> page 0) 1.0 0.35))
    (set-arrow-alpha! root-widget "arrow_down" (if (< page max-page) 1.0 0.35))))

;; ============================================================================
;; Network / RPC
;; ============================================================================

(defn query-terminal-state! [owner callback]
  (let [generation (runtime/ensure-owner! owner)]
    (net-client/send-to-server owner (terminal-messages/msg-id :get-state) {}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/query-response response)
          (when callback (callback response)))))))

(defn install-terminal! [owner callback]
  (let [generation (runtime/ensure-owner! owner)]
    (runtime/dispatch-event! owner :terminal/install-start nil)
    (net-client/send-to-server owner (terminal-messages/msg-id :install-terminal) {}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/install-result response)
          (when callback (callback response)))))))

(defn install-app! [owner app-id callback]
  (let [generation (runtime/ensure-owner! owner)]
    (runtime/dispatch-event! owner :terminal/install-app-start {:app-id app-id})
    (net-client/send-to-server owner (terminal-messages/msg-id :install-app) {:app-id (name app-id)}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/install-app-result (assoc response :app-id app-id))
          (when callback (callback response)))))))

(defn uninstall-app! [owner app-id callback]
  (let [generation (runtime/ensure-owner! owner)]
    (net-client/send-to-server owner (terminal-messages/msg-id :uninstall-app) {:app-id (name app-id)}
      (fn [response]
        (when (runtime/owner-active? owner generation)
          (runtime/dispatch-event! owner :terminal/uninstall-app-result (assoc response :app-id app-id))
          (when callback (callback response)))))))

;; ============================================================================
;; App grid widgets
;; ============================================================================

(defn- player-owner [player]
  (runtime/player-owner (or (player-uuid/player-uuid player) (str player))))

(defn- create-app-widget
  "Create app grid widget with staggered fade-in matching original
  AppHandler.onAdded + FrameEvent (TerminalUI.java:419-447).
  mAlpha = clamp(0, 1, (lifetime - (index+1)*0.1) / 0.4)"
  [app index installed? on-click creation-time]
  (let [[x y] (grid-position index)
        widget (cgui-core/create-widget :pos [x y] :size [(:app-width grid-config) (:app-height grid-config)])
        ;; Background texture
        bg (cgui-core/create-widget :pos [0 0] :size [151 151])
        bg-dt (comp/draw-texture tex-app-back 0x00000000)
        _ (comp/add-component! bg bg-dt)
        ;; Icon
        icon (cgui-core/create-widget :pos [9 32] :size [110 110])
        icon-texture (or (catalog/app-icon app) (modid/asset-path "textures" "guis/apps/default/icon.png"))
        icon-dt (comp/draw-texture icon-texture [255 255 255 0])
        _ (comp/add-component! icon icon-dt)
        ;; Text label
        text (cgui-core/create-widget :pos [0 148] :size [151 21])
        text-tb (comp/text-box :text (:name app) :font :ac-normal :font-size 32 :align :center :color 0x00000000)
        _ (comp/add-component! text text-tb)
        ;; Interaction
        _ (events/on-left-click widget (fn [_] (on-click app installed?)))
        ;; Alpha-based selection state (set by cursor hover detection)
        sel-alpha (atom 1.0)]
    (cgui-core/add-widget! widget bg)
    (cgui-core/add-widget! widget icon)
    (cgui-core/add-widget! widget text)
    ;; Staggered fade-in + selection highlight
    (let [delay-sec (* (inc index) 0.1) duration-sec 0.4
          base-alpha (if installed? 1.0 0.6)]
      (events/on-frame widget
        (fn [_]
          (let [lifetime (/ (- (System/currentTimeMillis) creation-time) 1000.0)
                entry-alpha (max 0.0 (min 1.0 (/ (- lifetime delay-sec) duration-sec)))
                m-alpha (* base-alpha entry-alpha)
                ;; Selection boost matching original: selected→1.3x icon, highlight bg
                sel-mult @sel-alpha
                bg-a (int (* 255 m-alpha sel-mult))
                icon-a (int (* 255 (if installed? 0.8 0.6) m-alpha sel-mult))
                text-a (int (* 255 (if installed? 0.82 0.2) m-alpha))]
            ;; Background — switch texture when selected
            (swap! (:state bg-dt) assoc
                   :texture (if (> sel-mult 1.0) tex-app-back-hdr tex-app-back)
                   :color (unchecked-int (bit-or (bit-shift-left bg-a 24) 0x00FFFFFF)))
            ;; Icon
            (swap! (:state icon-dt) assoc :color
                   (unchecked-int (bit-or (bit-shift-left icon-a 24) 0x00FFFFFF)))
            ;; Text
            (comp/set-text-color! text-tb
              (unchecked-int (bit-or (bit-shift-left text-a 24) 0x00FFFFFF)))))))
    ;; Return widget with selection atom attached
    (swap! (:metadata widget) assoc :sel-alpha sel-alpha)
    widget))

;; ============================================================================
;; UI update helpers
;; ============================================================================

(defn- update-app-count! [owner root-widget]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_appcount")]
    (let [state (runtime/state-snapshot owner)
          installed-count (count (:installed-apps state))
          total-count (count (:available-apps state))
          apps (catalog/ordered-apps)
          page (clamp-page apps (:page state))
          total-pages (page-count apps)
          hour (try (-> (System/currentTimeMillis) (quot 3600000) (mod 24) inc (mod 24))
                   (catch Throwable _ 12))
          minutes (try (-> (System/currentTimeMillis) (quot 60000) (mod 60))
                      (catch Throwable _ 0))
          time-text (str (when (< hour 10) "0") hour ":" (when (< minutes 10) "0") minutes)
          text (str installed-count "/" total-count " Applications, " time-text
                    "  P" (inc page) "/" total-pages)]
      (when-let [tb (comp/get-textbox-component text-widget)]
        (comp/set-text! tb text)))))

(defn- update-username! [root-widget player]
  (when-let [text-widget (cgui-core/find-widget root-widget "text_username")]
    (when-let [tb (comp/get-textbox-component text-widget)]
      (comp/set-text! tb (entity/player-get-name player)))))

(defn- update-loading-indicator! [owner root-widget]
  (let [loading? (:loading? (runtime/state-snapshot owner))]
    (when-let [icon-widget (cgui-core/find-widget root-widget "icon_loading")]
      (cgui-core/set-visible! icon-widget loading?))
    (when-let [text-widget (cgui-core/find-widget root-widget "text_loading")]
      (cgui-core/set-visible! text-widget loading?))))

;; ============================================================================
;; Continuous scroll — matching original mouseY edge detection
;; ============================================================================

(defn- setup-continuous-scroll!
  "Add mouseY-based continuous scrolling matching original TerminalUI.draw():
  mouseY==0 → scroll--, mouseY==MAX_MY → scroll++.
  Scrolls at ~3 px/frame at edges, matching original boundary behavior."
  [root-widget _player scroll-ref max-scroll-ref]
  (events/on-frame root-widget
    (fn [_]
      (let [my (double (or (get @(:metadata root-widget) :last-mouse-y) 0))
            screen-h (double (or (get @(:metadata root-widget) :screen-height) 480))
            scroll (int @scroll-ref)
            max-s (int @max-scroll-ref)]
        (when (pos? max-s)
          (when (< my scroll-boundary)
            (let [new-scroll (max 0 (- scroll 1))]
              (when (not= scroll new-scroll)
                (reset! scroll-ref new-scroll))))
          (when (> my (- screen-h scroll-boundary))
            (let [new-scroll (min max-s (+ scroll 1))]
              (when (not= scroll new-scroll)
                (reset! scroll-ref new-scroll)))))))))

;; ============================================================================
;; Selection cursor + highlight
;; ============================================================================

(defn- setup-selection-cursor!
  "Add cursor overlay widget + selection highlight matching original
  TerminalUI cursor rendering (HudUtils.rect with CURSOR texture).
  Tracks which grid cell the cursor is over and updates selection state."
  [root-widget app-widgets-ref]
  (let [sel-idx (atom -1)
        last-selected (atom -1)
        cursor-w (cgui-core/create-widget :pos [0 0] :size [40 40])
        cursor-dt (comp/draw-texture tex-cursor 0x66444444)]
    (comp/add-component! cursor-w cursor-dt)
    (cgui-core/add-widget! root-widget cursor-w)
    (events/on-frame root-widget
      (fn [_]
        (let [mx (double (or (get @(:metadata root-widget) :last-mouse-x) 0))
              my (double (or (get @(:metadata root-widget) :last-mouse-y) 0))
              csize (* 20 (+ 1 (Math/sin (/ (System/currentTimeMillis) 300.0)) 0.2))
              csize-select (if (>= @sel-idx 0) (* csize 1.3) csize)
              apps @app-widgets-ref
              new-selected (some (fn [[idx w]]
                                   (let [[wx wy] (cgui-core/get-pos w)
                                         [ww wh] (cgui-core/get-size w)]
                                     (when (and (>= mx wx) (< mx (+ wx ww))
                                                (>= my wy) (< my (+ wy wh)))
                                       idx)))
                                 (map-indexed vector apps))]
          (cgui-core/set-pos! cursor-w (- mx (/ csize-select 2)) (- my (/ csize-select 2) 120))
          (cgui-core/set-size! cursor-w (int csize-select) (int csize-select))
          (let [a (int (* 255 0.4 (if (>= @sel-idx 0) 1.5 1.0)))]
            (swap! (:state cursor-dt) assoc :color
                   (unchecked-int (bit-or (bit-shift-left a 24) 0x00FFFFFF))))
          (when (not= new-selected @sel-idx)
            (when (and (>= @sel-idx 0) (< @sel-idx (count apps)))
              (when-let [old-sel (get @(:metadata (nth apps @sel-idx)) :sel-alpha)]
                (reset! old-sel 1.0)))
            (reset! sel-idx (or new-selected -1))
            (when (>= @sel-idx 0)
              (when-let [new-sel (get @(:metadata (nth apps @sel-idx)) :sel-alpha)]
                (reset! new-sel 1.3)))))))
    sel-idx))

;; ============================================================================
;; 3D perspective effect — mouse-driven root transform
;; ============================================================================

(defn- setup-perspective-effect!
  "Add subtle mouse-driven transform to root widget, approximating
  original OpenGL perspective rotation:
  glRotated(-18 - 4*(buffX/MAX_MX-0.5) + sin, 0,1,0)
  glRotated(7 + 4*(buffY/MAX_MY-0.5), 1,0,0)
  Using CGUI :transform component with translate for 2D approximation."
  [root-widget]
  (let [tf (comp/transform :scale-x 1.0 :scale-y 1.0 :translate-x 0.0 :translate-y 0.0)
        buff-x (atom 150.0) buff-y (atom 150.0)
        last-frame (atom 0)]
    (comp/add-component! root-widget tf)
    (events/on-frame root-widget
      (fn [_]
        (let [mx (double (or (get @(:metadata root-widget) :last-mouse-x) 300))
              my (double (or (get @(:metadata root-widget) :last-mouse-y) 370))
              now (/ (double (System/currentTimeMillis)) 1000.0)
              dt (max 0.001 (- now @last-frame))]
          (reset! last-frame now)
          (reset! buff-x (let [d (- mx @buff-x)]
                           (+ @buff-x (* (Math/min (* balance-speed dt) (Math/abs d))
                                        (Math/signum d)))))
          (reset! buff-y (let [d (- my @buff-y)]
                           (+ @buff-y (* (Math/min (* balance-speed dt) (Math/abs d))
                                        (Math/signum d)))))
          (let [tx (* -4.0 (- (/ @buff-x max-mx) 0.5))
                ty (* 3.0 (- (/ @buff-y max-my) 0.5))
                scl (+ 1.0 (* 0.02 (Math/sin (* now 1.0))))]
            (swap! (comp/component-state tf) assoc
                   :translate-x tx :translate-y ty
                   :scale-x scl :scale-y scl)))))))

;; ============================================================================
;; App grid rebuild + interaction
;; ============================================================================

(declare rebuild-app-grid!)

(defn- handle-app-click [app installed? player root-widget cursor-sel-idx-ref]
  (let [owner (player-owner player)
        app-id (:id app)]
    (if installed?
      (client-apps/launch! app-id player)
      (install-app! owner app-id
        (fn [response]
          (when (:success response)
            (rebuild-app-grid! root-widget player cursor-sel-idx-ref))
          (when-not (:success response)
            (log/error "Failed to install app:" app-id)))))))

(declare rebuild-app-grid!)

(defn rebuild-app-grid!
  [root-widget player cursor-sel-idx-ref]
  (let [owner (player-owner player)]
    (runtime/with-owner owner
      (try
        (doseq [i (range 9)]
          (when-let [old-widget (cgui-core/find-widget root-widget (str "app_" i))]
            (cgui-core/remove-widget! root-widget old-widget)))
        (let [state (runtime/state-snapshot owner)
              all-apps (catalog/ordered-apps)
              page (clamp-page all-apps (:page state))
              installed-apps (:installed-apps state)
              _ (runtime/dispatch-event! owner :terminal/set-page {:page page})
              offset (* page apps-per-page)
              page-apps (->> all-apps (drop offset) (take apps-per-page))
              now-ms (System/currentTimeMillis)
              app-widgets (atom [])]
          (doseq [[index app] (map-indexed vector page-apps)]
            (let [installed? (contains? installed-apps (:id app))
                  app-widget (create-app-widget app index installed?
                                (fn [a inst?]
                                  (handle-app-click a inst? player root-widget cursor-sel-idx-ref))
                                now-ms)]
              (cgui-core/set-name! app-widget (str "app_" index))
              (cgui-core/add-widget! root-widget app-widget)
              (swap! app-widgets conj app-widget)))
          ;; Update scroll max for continuous scroll
          (reset! cursor-sel-idx-ref (min @cursor-sel-idx-ref (dec (count page-apps))))
          (reset! (get-in @(:metadata root-widget) [:scroll-ref] (atom 0)) 0))
        (update-app-count! owner root-widget)
        (update-loading-indicator! owner root-widget)
        (update-arrow-state! root-widget (catalog/ordered-apps) (:page (runtime/state-snapshot owner)))
        (catch Exception e
          (log/error "Error rebuilding app grid:" (ex-message e)))))))

;; ============================================================================
;; Page navigation
;; ============================================================================

(defn- change-page! [delta root-widget player cursor-sel-idx-ref]
  (let [owner (player-owner player)]
    (runtime/with-owner owner
      (let [apps (catalog/ordered-apps)
            current (:page (runtime/state-snapshot owner))
            next-page (clamp-page apps (+ (int (or current 0)) (int delta)))]
        (when (not= next-page current)
          (runtime/dispatch-event! owner :terminal/set-page {:page next-page})
          (rebuild-app-grid! root-widget player cursor-sel-idx-ref))))))

(defn- bind-navigation-controls! [root-widget player cursor-sel-idx-ref]
  (when-let [up (cgui-core/find-widget root-widget "arrow_up")]
    (events/unlisten! up :left-click)
    (events/on-left-click up (fn [_] (change-page! -1 root-widget player cursor-sel-idx-ref))))
  (when-let [down (cgui-core/find-widget root-widget "arrow_down")]
    (events/unlisten! down :left-click)
    (events/on-left-click down (fn [_] (change-page! 1 root-widget player cursor-sel-idx-ref)))))

;; ============================================================================
;; Main GUI builder
;; ============================================================================

(defn create-terminal-gui [player]
  (try
    (log/info "Creating terminal GUI for player:" (entity/player-get-name player))
    (let [owner (player-owner player)
          root-widget (cgui-doc/read-xml (modid/asset-path "guis" "terminal.xml"))
          cursor-sel-idx-ref (atom -1)]
      (runtime/with-owner owner
        (if-not root-widget
          (cgui-core/create-widget :size [640 785])
          (do
            (update-username! root-widget player)
            ;; 3D perspective effect (matching original OpenGL rotation)
            (setup-perspective-effect! root-widget)
            (bind-navigation-controls! root-widget player cursor-sel-idx-ref)
            (query-terminal-state! owner
              (fn [_] (rebuild-app-grid! root-widget player cursor-sel-idx-ref)))
            (when-let [template (cgui-core/find-widget root-widget "app_template")]
              (cgui-core/set-visible! template false))
            ;; Selection cursor + highlight overlay
            (setup-selection-cursor! root-widget (atom []))
            ;; Continuous scroll via mouseY edges
            (setup-continuous-scroll! root-widget player (atom 0) (atom 0))
            ;; Periodic loading indicator update
            (events/on-frame root-widget
              (let [counter (atom 0)]
                (fn [_]
                  (swap! counter inc)
                  (when (zero? (mod @counter 40))
                    (runtime/with-owner owner
                      (update-loading-indicator! owner root-widget))))))
            (log/info "Terminal GUI created successfully")
            root-widget))))
    (catch Exception e
      (log/error "Error creating terminal GUI:" (ex-message e))
      (cgui-core/create-widget :size [640 785]))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-terminal [player]
  (log/info "Opening terminal for player:" (entity/player-get-name player))
  (try
    (let [owner (player-owner player)]
      (query-terminal-state! owner
        (fn [response]
          (if (:terminal-installed? response)
            (client-bridge/open-screen! :ac/terminal {:player player})
            (log/info "Terminal not installed, use item to install first")))))
    (catch Exception e
      (log/error "Error opening terminal:" (ex-message e)))))

(defn install-ui-hooks! []
  (with-init-guard terminal-ui-hooks-installed?
    (platform-ui/register-widget-factory!
      :ac/terminal-gui
      (fn [{:keys [player]}] (create-terminal-gui player)))
    (net-client/register-push-handler!
      (terminal-messages/msg-id :terminal-install-effect)
      (fn [_payload]
        (when-let [player (client-bridge/get-client-player)]
          (install-effect/show! player))))
    (log/info "AC terminal UI hooks installed"))
  nil)

(defn toggle-terminal! [player]
  (if (client-bridge/screen-active?)
    (client-bridge/close-screen!)
    (open-terminal player)))
