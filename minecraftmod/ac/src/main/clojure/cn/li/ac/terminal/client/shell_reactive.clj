(ns cn.li.ac.terminal.client.shell-reactive
  "Complete reactive replacement for terminal/client/shell.clj's GUI.
   All network/state/page logic (query/install/uninstall/launch, page-count,
   clamp-page, grid-position, player-owner) is reused verbatim from shell.clj.
   Only the CGUI widget tree + per-frame widget mutation is rewritten native.

   Simplifications versus the original (cosmetic-only, no functional loss):
   - Per-cell staggered fade-in + breathing cursor decal omitted — app grid
     appears instantly; hover feedback is native (idle/hover alpha on the
     app icon image, driven by the framework's own hoveredIdx tracking).
   - 3D OpenGL rotation → the same 2D translate approximation the CGUI
     version already used, now driven by real mouse-position signals."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.terminal.client.apps :as client-apps]
            [cn.li.ac.terminal.client.shell :as shell]
            [cn.li.ac.terminal.client.runtime :as term-rt]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.mcmod.client.platform-bridge :as bridge]
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
           [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.signal ISigO]))

(def ^:private root-w 640.0)
(def ^:private root-h 785.0)
(def ^:private apps-per-page 9)

;; ============================================================================
;; set-tick! — force a per-frame side-effecting computed-o to actually run.
;; ComputedO/ComputedD are lazy-pull: without a real Binding reader, a bare
;; put-user-signal! computed never executes (see developer panel-reactive.clj
;; for the fuller writeup of this framework quirk).
;; ============================================================================

(defn- pull-o! [_node source] (.sGet ^ISigO source) nil)

(defn- hover-alpha-step [idle-a hover-a ^UiRt rt idx _ms]
  (if (= (long idx) (rt/hovered-idx rt)) (double hover-a) (double idle-a)))

(defn- bind-hover-alpha! [^UiRt rt id idle-a hover-a]
  (let [^INode n (rt/node-by-id rt id)
        idx (.getIdx n)
        clock (rt/clock-ms-sig rt)
        sig-d (sig/computed-d [clock] (partial hover-alpha-step idle-a hover-a rt idx))]
    (ui/bind! rt id :alpha sig-d)))

(defn- set-tick! [^UiRt rt key computed-sig]
  (when-let [old (rt/user-signal rt key)] (sig/unbind! old))
  (if computed-sig
    (let [^INode anchor (rt/node-by-id rt :back)
          b (sig/bind! computed-sig anchor pull-o! (rt/get-dirty-bindings-q rt))]
      (rt/register-binding! rt (.getIdx anchor) b)
      (rt/put-user-signal! rt key b))
    (rt/put-user-signal! rt key nil)))

;; ============================================================================
;; App grid — dynamic group rebuilt per page
;; ============================================================================

(defn- app-item-spec [id app x y]
  {:kind :image
   :props {:id id :x (double x) :y (double y) :w 151.0 :h 151.0
           :src (modid/asset-path "textures" "guis/data_terminal/app_back.png")}
   :children
   [{:kind :image
     :props {:id (keyword (str (name id) "-icon")) :x 9.0 :y 32.0 :w 110.0 :h 110.0
             :src (or (catalog/app-icon app) (modid/asset-path "textures" "guis/apps/default/icon.png"))}}
    {:kind :text
     :props {:id (keyword (str (name id) "-text")) :x 0.0 :y 148.0 :w 151.0 :h 21.0
             :text (:name app "?") :font-size 16.0 :color 0xFFFFFFFF}}]})

(defn- handle-app-click! [_rt owner app installed? player rebuild!]
  (if installed?
    (client-apps/launch! (:id app) player)
    (shell/install-app! owner (:id app)
      (fn [response]
        (if (:success response)
          (rebuild!)
          (log/error "Failed to install app:" (:id app)))))))

(defn- rebuild-grid!
  [^UiRt rt owner player]
  (let [grid ^INode (rt/node-by-id rt :app-grid)
        _ (rt/clear-children! rt grid)
        state (term-rt/state-snapshot owner)
        all-apps (catalog/ordered-apps)
        page (shell/clamp-page all-apps (:page state))
        _ (term-rt/dispatch-event! owner :terminal/set-page {:page page})
        installed-apps (:installed-apps state)
        offset (* page apps-per-page)
        page-apps (->> all-apps (drop offset) (take apps-per-page))
        total-pages (shell/page-count all-apps)]
    (doseq [[i app] (map-indexed vector page-apps)]
      (let [[x y] (shell/grid-position i)
            id (keyword (str "app-" i))
            installed? (contains? installed-apps (:id app))]
        (rt/build-child! rt (app-item-spec id app x y) grid)
        (bind-hover-alpha! rt id 0.85 1.0)
        (events/on! rt id :left-click
          (fn [_ _ _]
            (handle-app-click! rt owner app installed? player
              (fn [] (rebuild-grid! rt owner player)))))))
    (ui/set-prop! rt :arrow_up :alpha (if (> page 0) 1.0 0.35))
    (ui/set-prop! rt :arrow_down :alpha (if (< page (dec total-pages)) 1.0 0.35))))

;; ============================================================================
;; Page navigation
;; ============================================================================

(defn- change-page! [^UiRt rt owner player delta]
  (let [apps (catalog/ordered-apps)
        current (:page (term-rt/state-snapshot owner))
        next-page (shell/clamp-page apps (+ (int (or current 0)) (int delta)))]
    (when (not= next-page current)
      (term-rt/dispatch-event! owner :terminal/set-page {:page next-page})
      (rebuild-grid! rt owner player))))

;; ============================================================================
;; Header display — username / time / app count / loading indicator
;; ============================================================================

(defn- attach-header-tick! [^UiRt rt owner player]
  (ui/set-prop! rt :text_username :text (entity/player-get-name player))
  (set-tick! rt :header-tick
    (sig/computed-o [(rt/clock-ms-sig rt)]
      (fn [ms]
        (let [t (long (/ (long ms) 1000))
              hour (mod (quot t 3600) 24) minutes (mod (quot t 60) 60)
              time-text (format "%02d:%02d" hour minutes)
              state (term-rt/state-snapshot owner)
              all-apps (catalog/ordered-apps)
              page (shell/clamp-page all-apps (:page state))
              total-pages (shell/page-count all-apps)
              installed-count (count (:installed-apps state))
              total-count (count all-apps)
              loading? (boolean (:loading? state))]
          (ui/set-prop! rt :text_appcount
            :text (str installed-count "/" total-count " Applications, " time-text
                       "  P" (inc page) "/" total-pages))
          (let [^INode li (rt/node-by-id rt :icon_loading)
                ^INode lt (rt/node-by-id rt :text_loading)]
            (when li (.setVisible li loading?) (.setFlag li node/FLAG-LAYOUT-DIRTY))
            (when lt (.setVisible lt loading?) (.setFlag lt node/FLAG-LAYOUT-DIRTY))))
        nil))))

;; ============================================================================
;; 3D-perspective approximation — real mouse-position signals via the
;; framework's :on-pointer-move hook (fires on every mouse-move regardless
;; of hit-test result), translating the "back" root a few px per axis.
;; ============================================================================

(defn- attach-perspective! [^UiRt rt]
  (let [mouse-x (sig/signal-d (/ root-w 2.0))
        mouse-y (sig/signal-d (/ root-h 2.0))
        ^INode back (rt/node-by-id rt :back)]
    (rt/put-user-signal! rt :on-pointer-move
      (fn [mx my] (sig/sset-d! mouse-x mx) (sig/sset-d! mouse-y my)))
    (set-tick! rt :perspective-tick
      (sig/computed-o [(rt/clock-ms-sig rt)]
        (fn [_]
          (let [nx (- (/ (sig/sget-d mouse-x) root-w) 0.5)
                ny (- (/ (sig/sget-d mouse-y) root-h) 0.5)]
            (.setX back (* -8.0 nx))
            (.setY back (* 6.0 ny))
            (.setFlag back node/FLAG-LAYOUT-DIRTY))
          nil)))))

;; ============================================================================
;; Entry point
;; ============================================================================

(defn create-runtime [player]
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/asset-path "guis" "new/terminal.xml"))
        _ (rt/build! r spec)
        owner (shell/player-owner player)]
    (rt/build-child! r
      {:kind :group :props {:id :app-grid :x 0.0 :y 0.0 :w root-w :h root-h}}
      (rt/node-by-id r :back))
    (let [^INode tmpl (rt/node-by-id r :app_template)]
      (when tmpl (.setVisible tmpl false) (.setFlag tmpl node/FLAG-LAYOUT-DIRTY)))
    (let [^INode li (rt/node-by-id r :icon_loading) ^INode lt (rt/node-by-id r :text_loading)]
      (when li (.setVisible li false)) (when lt (.setVisible lt false)))
    (attach-header-tick! r owner player)
    (attach-perspective! r)
    (events/on! r :arrow_up :left-click (fn [_ _ _] (change-page! r owner player -1)))
    (events/on! r :arrow_down :left-click (fn [_ _ _] (change-page! r owner player 1)))
    (shell/query-terminal-state! owner
      (fn [_] (rebuild-grid! r owner player)))
    r))

(defn open! [player]
  (let [r (create-runtime player)]
    (bridge/open-reactive-screen! r "Terminal")))

(defn create-terminal-gui-reactive
  "Widget-factory-compatible entry point: returns a {:type :reactive-screen}
   map for the platform's open-screen dispatcher, matching the shape used by
   skill-tree/preset-editor screens."
  [player]
  {:type :reactive-screen :runtime (create-runtime player)})

(defn install-ui-hooks-reactive!
  "Registers the reactive terminal screen under the same factory key used by
   shell.clj's install-ui-hooks!, plus reuses shell.clj's GLFW Alt-key
   polling (unchanged — it's plain client-tick logic, not CGUI-specific)."
  []
  (platform-ui/register-widget-factory!
    :ac/terminal-gui
    (fn [{:keys [player]}] (create-terminal-gui-reactive player)))
  (content-actions/register-client-tick-hook! shell/poll-terminal-toggle-key!)
  (log/info "AC terminal UI hooks installed (reactive)"))
