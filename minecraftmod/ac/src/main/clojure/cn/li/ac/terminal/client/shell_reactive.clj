(ns cn.li.ac.terminal.client.shell-reactive
  "Complete reactive terminal UI aligned with upstream AcademyCraft TerminalUI.
   Features: 3D perspective (PoseStack, via mc-1.20.1 bridge), mouse edge
   scrolling, custom cursor with additive blend, selection highlight + audio,
   stagger fade-in, game-time clock, and loading animation (sine-wave alpha).

   MC-specific rendering (3D perspective + cursor) is delegated to
   cn.li.mc1201.gui.reactive.terminal-render via platform bridge ops
   :terminal-apply-perspective! and :terminal-render-cursor!."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.terminal.client.apps :as client-apps]
            [cn.li.ac.terminal.client.runtime :as term-rt]
            [cn.li.ac.terminal.messages :as terminal-messages]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.ui :as platform-ui]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

;; ============================================================================
;; Constants — matching upstream AcademyCraft TerminalUI
;; ============================================================================

(def ^:private root-w 640.0)
(def ^:private root-h 785.0)
(def ^:private max-mx 605.0)     ;; MAX_MX
(def ^:private max-my 740.0)     ;; MAX_MY
(def ^:private balance-speed 3000.0)
(def ^:private sensitivity 0.7)

;; Grid positioning (upstream: START_X=65, START_Y=155, STEP_X=180, STEP_Y=180)
(def ^:private start-x 65.0)
(def ^:private start-y 155.0)
(def ^:private step-x 180.0)
(def ^:private step-y 180.0)
(def ^:private app-w 151.0)
(def ^:private app-h 151.0)

;; ============================================================================
;; Network / RPC (reused verbatim from original shell.clj design)
;; ============================================================================

(defn- query-terminal-state! [owner callback]
  (let [generation (term-rt/ensure-owner! owner)]
    (net-client/send-to-server owner (terminal-messages/msg-id :get-state) {}
      (fn [response]
        (when (term-rt/owner-active? owner generation)
          (term-rt/dispatch-event! owner :terminal/query-response response)
          (when callback (callback response)))))))

(defn- player-owner [player]
  (term-rt/player-owner (or (player-uuid/player-uuid player) (str player))))

;; ============================================================================
;; create-runtime — main constructor
;; ============================================================================

(defn create-runtime [player]
  (let [r (rt/create-runtime)

        ;; ===== Instance-local frame state (primitive arrays, zero allocation) =====
        ;; fd: [0]mouse-x [1]mouse-y [2]buff-x [3]buff-y
        ;;     [4]last-mx [5]last-my [6]last-frame-ms [7]create-time-ms [8]aspect
        ^doubles fd (doto (double-array 9)
                      (aset 0 150.0) (aset 1 150.0)    ;; mouse-x, mouse-y
                      (aset 2 150.0) (aset 3 150.0)    ;; buff-x, buff-y
                      (aset 6 (double (System/currentTimeMillis))))  ;; last-frame-ms
        ;; fi: [0]scroll [1]selection [2]last-selection [3]last-installed-count
        ^ints fi (doto (int-array 4)
                   (aset 2 -1)    ;; last-selection = -1
                   (aset 3 -1))   ;; last-installed-count = -1

        ;; ===== Per-instance constants =====
        owner (player-owner player)
        create-time-ms (double (System/currentTimeMillis))

        ;; forward decl (cyclic: update-grid! references create-time-ms)
        update-grid!-fn (volatile! nil)

        ;; ===== 1. Build XML UI =====
        spec (ui-xml/load-spec (modid/asset-path "guis" "new/terminal.xml"))
        _ (rt/build! r spec)

        ;; ===== 2. Create app widget pool (9 slots, one-time allocation) =====
        _ (do
            (let [^INode grid (rt/node-by-id r :app-grid)
                  ^INode tmpl (rt/node-by-id r :app_template)]
              (.setVisible tmpl false)
              (dotimes [i 9]
                (let [id (keyword (str "app-" i))]
                  (rt/build-child! r
                    {:kind :image
                     :props {:id id :x 0.0 :y 0.0 :w app-w :h app-h
                             :src (modid/asset-path "textures" "guis/data_terminal/app_back.png")}
                     :children
                     [{:kind :image
                       :props {:id (keyword (str "app-" i "-icon"))
                               :x 9.0 :y 32.0 :w 110.0 :h 110.0
                               :src ""}}
                      {:kind :text
                       :props {:id (keyword (str "app-" i "-text"))
                               :x 0.0 :y 148.0 :w 151.0 :h 21.0
                               :text "" :font-size 16.0 :color 0xFFFFFFFF}}]}
                    grid)
                  (.setVisible ^INode (rt/node-by-id r id) false)))))

        ;; ===== 3. Hide loading indicators initially =====
        _ (do (.setVisible ^INode (rt/node-by-id r :icon_loading) false)
              (.setVisible ^INode (rt/node-by-id r :text_loading) false))

        ;; ===== 4. pre-render hook — frame state update + MC 3D perspective =====
        pre-render
        (fn pre-render-fn [_gg ^UiRt rt* mx my _pt]
          (let [now-ms (double (System/currentTimeMillis))
                dt (max 0.001 (/ (- now-ms (aget fd 6)) 1000.0))
                ;; Mouse delta integration (upstream: mouseX += helper.dx * SENSITIVITY)
                dx (* (- mx (aget fd 4)) sensitivity)
                dy (* (- my (aget fd 5)) sensitivity)
                new-mx (max 0.0 (min max-mx (+ (aget fd 0) dx)))
                new-my (max 0.0 (min max-my (- (aget fd 1) dy)))
                ;; Smooth balance
                balance (fn bal [from to]
                          (let [d (double (- (double to) (double from)))]
                            (double (+ (double from)
                                       (* (min (* balance-speed dt) (Math/abs d))
                                          (Math/signum d))))))
                new-bx (balance (aget fd 2) new-mx)
                new-by (balance (aget fd 3) new-my)
                ;; Selection (3x3 grid index)
                new-sel (let [col (int (/ (max 0.0 (min (dec max-mx) new-bx))
                                          (/ max-mx 3.0)))
                              row (int (/ (max 0.0 (min (dec max-my) new-by))
                                          (/ max-my 3.0)))]
                          (min 8 (+ (* row 3) col)))
                ;; Edge scrolling
                installed-count (count (:installed-apps (term-rt/state-snapshot owner)))
                max-scroll (max 0 (- (int (Math/ceil (/ (double installed-count) 3.0))) 3))
                [new-scroll new-my]  ;; returns [scroll my] — adjusts my after edge trigger
                (cond
                  (<= new-my 0.0)
                  [(max 0 (dec (aget fi 0))) 1.0]
                  (>= new-my max-my)
                  [(min max-scroll (inc (aget fi 0))) (dec max-my)]
                  :else
                  [(aget fi 0) new-my])
                t-ms (double now-ms)]
            ;; Write frame state to primitive arrays
            (aset fd 0 (double new-mx)) (aset fd 1 (double new-my))
            (aset fd 2 (double new-bx)) (aset fd 3 (double new-by))
            (aset fd 4 (double mx)) (aset fd 5 (double my))
            (aset fd 6 (double now-ms))
            ;; Save old state before overwriting (for change detection below)
            (let [old-scroll (aget fi 0)]
              (aset fi 0 (int new-scroll)) (aset fi 1 new-sel)
              ;; Delegate MC 3D perspective transform via platform bridge
              (bridge/call-adapter :terminal-apply-perspective! _gg rt* mx my _pt)
              ;; --- Selection change → grid update + audio ---
              (when (not= new-sel (aget fi 2))
                (aset fi 2 new-sel)
                (let [installed (vec (:installed-apps (term-rt/state-snapshot owner)))
                      app-idx (+ (* (int new-scroll) 3) new-sel)]
                  (when (< app-idx (count installed))
                    (client-sounds/queue-current-sound-effect!
                      {:type :sound :sound-id (str (modid/MOD-ID) ":terminal.select")
                       :volume 0.2 :pitch 1.0})))
                (when-let [f @update-grid!-fn] (f)))
              ;; --- Scroll change or installed-count change → grid update ---
              (when (or (not= (int new-scroll) old-scroll)
                        (not= installed-count (aget fi 3)))
                (aset fi 3 installed-count)
                (when-let [f @update-grid!-fn] (f))))
            ;; --- Header display update ---
            (let [game-ticks (long (or (sig/sget-l (rt/game-ticks-sig r)) 0))
                  day-time (mod game-ticks 24000)
                  hour (int (/ day-time 1000))
                  minutes (int (/ (* (mod day-time 1000) 60) 1000))
                  time-text (format "%02d:%02d" hour minutes)
                  state (term-rt/state-snapshot owner)
                  installed-count (count (:installed-apps state))
                  loading? (boolean (:loading? state))
                  loading-alpha (if loading?
                                  (+ 0.1 (* 0.45 (inc (Math/sin (* t-ms 0.005)))))
                                  0.0)]
              (ui/set-prop! r :text_appcount :text
                (str installed-count " Applications, " time-text))
              (ui/set-prop! r :text_username :text (entity/player-get-name player))
              (ui/set-prop! r :icon_loading :alpha loading-alpha)
              (ui/set-prop! r :text_loading :alpha loading-alpha)
              (let [^INode li (rt/node-by-id r :icon_loading)
                    ^INode lt (rt/node-by-id r :text_loading)]
                (.setVisible li loading?) (.setVisible lt loading?))
              (ui/set-prop! r :arrow_up :alpha (if (> (aget fi 0) 0) 1.0 0.35))
              (ui/set-prop! r :arrow_down :alpha
                (if (< (aget fi 0) max-scroll) 1.0 0.35)))))

        ;; ===== 5. post-render hook — MC cursor rendering =====
        post-render
        (fn post-render-fn [_gg ^UiRt rt* _mx _my _pt]
          (bridge/call-adapter :terminal-render-cursor! _gg rt* _mx _my _pt))

        ;; ===== 6. App grid update (batch, called on scroll/selection change) =====
        update-grid!
        (fn update-grid-fn []
          (let [installed (vec (:installed-apps (term-rt/state-snapshot owner)))
                scroll (aget fi 0) sel (aget fi 1)
                start-idx (* scroll 3)
                lifetime (/ (- (double (System/currentTimeMillis)) create-time-ms) 1000.0)]
            (dotimes [i 9]
              (let [id (keyword (str "app-" i))
                    ^INode w (rt/node-by-id r id)
                    app-idx (+ start-idx i)
                    has-app (< app-idx (count installed))]
                (if has-app
                  (let [app (nth installed app-idx)
                        col (rem i 3) row (quot i 3)
                        x (+ start-x (* step-x (double col)))
                        y (+ start-y (* step-y (double row)))
                        selected? (= i sel)
                        ;; Stagger fade-in: clamp((lifetime-(id+1)*0.1)/0.4, 0, 1)
                        mAlpha (max 0.0 (min 1.0 (/ (- lifetime (* (+ app-idx 1) 0.1)) 0.4)))
                        icon-id (keyword (str "app-" i "-icon"))
                        text-id (keyword (str "app-" i "-text"))
                        bg-alpha mAlpha
                        icon-alpha (* (if selected? 0.8 0.6) mAlpha)
                        text-alpha (float (+ 0.1 (* (if selected? 0.72 0.1) mAlpha)))]
                    (.setVisible w true)
                    (.setX w (double x)) (.setY w (double y))
                    (.setFlag w node/FLAG-LAYOUT-DIRTY)
                    ;; Background: highlight texture when selected (upstream APP_BACK / APP_BACK_HDR)
                    (ui/set-prop! r id :src
                      (if selected?
                        (modid/asset-path "textures" "guis/data_terminal/app_back_highlight.png")
                        (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
                    (ui/set-prop! r id :alpha bg-alpha)
                    (ui/set-prop! r icon-id :alpha icon-alpha)
                    (ui/set-prop! r text-id :alpha text-alpha)
                    (ui/set-prop! r text-id :text (:name app "?"))
                    (when-let [icon-src (catalog/app-icon app)]
                      (ui/set-prop! r icon-id :src icon-src)))
                  (.setVisible w false))))))

        ;; Resolve forward decl
        _ (vreset! update-grid!-fn update-grid!)

        ;; ===== 7. Event handlers =====
        _ (events/on! r :back :left-click
            (fn [_ _ _]
              (let [installed (vec (:installed-apps (term-rt/state-snapshot owner)))
                    scroll (aget fi 0) sel (aget fi 1)
                    app-idx (+ (* scroll 3) sel)]
                (when (< app-idx (count installed))
                  (let [app-id (:id (nth installed app-idx))]
                    (client-apps/launch! app-id player))))))

        ;; ===== 8. Initial query + first render =====
        _ (query-terminal-state! owner
            (fn [_]
              (term-rt/dispatch-event! owner :terminal/set-page {:page 0})
              (update-grid!)))

        ;; ===== 9. Store hooks + frame state in user-signals =====
        _ (rt/put-user-signal! r :terminal-fd fd)        ;; frame doubles (MC render reads this)
        _ (rt/put-user-signal! r :terminal-fi fi)        ;; frame ints
        _ (rt/put-user-signal! r :terminal-owner owner)
        _ (rt/put-user-signal! r :terminal-pre-render pre-render)
        _ (rt/put-user-signal! r :terminal-post-render post-render)
        _ (rt/put-user-signal! r :terminal-on-close
            #(term-rt/clear-state! owner))]
    r))

;; ============================================================================
;; Entry points
;; ============================================================================

(defn open! [player]
  (let [r (create-runtime player)]
    (bridge/open-reactive-screen! r "Terminal"
      {:on-pre-render (rt/user-signal r :terminal-pre-render)
       :on-post-render (rt/user-signal r :terminal-post-render)
       :on-close (rt/user-signal r :terminal-on-close)})))

(defn open-terminal!
  "Query install state first; only open if terminal is installed.
   Shows chat message 'ac.terminal.notinstalled' if not installed."
  [player]
  (let [owner (player-owner player)]
    (query-terminal-state! owner
      (fn [response]
        (if (:terminal-installed? response)
          (open! player)
          (do
            (bridge/send-system-message! player "ac.terminal.notinstalled")
            (log/info "Terminal not installed, use item to install first")))))))

(defn toggle! [player]
  (if (bridge/screen-active?)
    (bridge/close-screen!)
    (open-terminal! player)))

;; ============================================================================
;; Widget factory + install (preserved for existing callers)
;; ============================================================================
;; NOTE: the Left Alt toggle key is dispatched through the universal keyboard
;; protocol (:content/toggle-terminal in cn.li.ac.input-ids): Forge fires it
;; via KeyMapping events, Fabric via glfw-polling-core. The former private
;; GLFW poll here double-fired with the Forge path (one Alt press = open+close)
;; and ignored open screens — do not reintroduce it.

(defn create-terminal-gui-reactive
  "Widget-factory-compatible entry point."
  [player]
  {:type :reactive-screen :runtime (create-runtime player)})

(defn install-ui-hooks-reactive!
  "Registers the reactive terminal screen under :ac/terminal-gui factory key."
  []
  (platform-ui/register-widget-factory!
    :ac/terminal-gui
    (fn [{:keys [player]}] (create-terminal-gui-reactive player)))
  (log/info "AC terminal UI hooks installed (reactive)"))
