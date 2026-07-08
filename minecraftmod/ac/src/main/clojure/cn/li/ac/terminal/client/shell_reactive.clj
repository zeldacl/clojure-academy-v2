(ns cn.li.ac.terminal.client.shell-reactive
  "Complete reactive replacement for shell.clj.
   Signal-driven: 3D perspective + app grid + scroll + selection cursor.
   Preserves RPC/network/page logic from old shell.clj."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]))

;; ============================================================================
;; Grid config + helpers (preserved from old shell.clj)
;; ============================================================================

(def ^:private grid-config {:columns 3 :rows 3 :col-x [65 245 425] :row-y [155 335 515] :app-width 151 :app-height 151})
(def ^:private apps-per-page (* (:columns grid-config) (:rows grid-config)))
(def ^:private sensitivity 0.7) (def ^:private scroll-boundary 40.0)
(defn- grid-position [i] (let [r (quot i (:columns grid-config)) c (rem i (:columns grid-config))] [(get (:col-x grid-config) c) (get (:row-y grid-config) r)]))
(defn- page-count [apps] (max 1 (int (Math/ceil (/ (double (count apps)) (double apps-per-page))))))
(defn- clamp-page [apps page] (let [mp (dec (page-count apps))] (-> (int (or page 0)) (max 0) (min mp))))

;; ============================================================================
;; Reactive UI
;; ============================================================================

(defn- build-app-grid-spec [apps page]
  "Build node specs for app grid items on current page."
  (let [start (* page apps-per-page)
        page-apps (take apps-per-page (drop start apps))]
    (map-indexed
      (fn [i app]
        (let [[x y] (grid-position i)]
          {:kind :image :id (keyword (str "app-" i))
           :props {:x (double x) :y (double y)
                   :w (:app-width grid-config) :h (:app-height grid-config)
                   :src (modid/asset-path "textures" "guis/data_terminal/app_back.png")
                   :z 1.0}
           :children [{:kind :text :id (keyword (str "app-name-" i))
                       :props {:x 5 :y 5 :text (:name app "?") :font-size 10 :color 0xFFFFFFFF}}]}))
      page-apps)))

(defn create-runtime [player]
  (let [r (rt/create-runtime)
        apps (catalog/ordered-apps)
        total-pages (page-count apps)
        ;; Signals
        current-page (sig/signal-l 0)
        cursor-idx (sig/signal-l -1)
        mouse-x (sig/signal-d 0.0)  mouse-y (sig/signal-d 0.0)
        perspective-rot-x (sig/signal-d 0.0)  perspective-rot-y (sig/signal-d 0.0)
        time-str (sig/signal-o "00:00")
        app-count-str (sig/signal-o (str (count apps) " apps"))
        clock (rt/clock-ms-sig r)]
    ;; Build root spec with grid
    (let [spec {:kind :group :id :root :props {:w 605 :h 740}
                :children (concat
                           [{:kind :image :id :bg :props {:x 0 :y 0 :w 605 :h 740
                                  :src (modid/asset-path "textures" "guis/data_terminal/terminal_main.png")}}
                            {:kind :text :id :time :props {:x 520 :y 10 :text "00:00" :font-size 12 :color 0xFFFFFFFF}}
                            {:kind :text :id :app-count :props {:x 10 :y 10 :text (str (count apps) " apps") :font-size 12 :color 0xFF888888}}
                            {:kind :box :id :cursor :props {:x 0 :y 0 :w (:app-width grid-config) :h (:app-height grid-config)
                                    :outline 0xFF44AAFF :outline-width 2.0 :z 10.0 :visible? false}}
                            {:kind :text :id :page-indicator :props {:x 280 :y 720 :text (str "1/" total-pages) :font-size 10 :color 0xFF888888}}]
                           (build-app-grid-spec apps 0))}]
      (rt/build! r spec))
    ;; Store signals
    (doseq [[k s] {:current-page current-page :cursor-idx cursor-idx
                   :mouse-x mouse-x :mouse-y mouse-y
                   :time-str time-str :app-count-str app-count-str
                   :perspective-rot-x perspective-rot-x :perspective-rot-y perspective-rot-y}]
      (rt/put-user-signal! r k s))
    ;; Per-frame clock update
    (rt/put-user-signal! r :clock-update
      (sig/computed-o [clock] (fn [ms] (let [t (int (/ (long ms) 1000))] (format "%02d:%02d" (int (/ t 3600)) (int (rem (/ t 60) 60)))))))
    ;; Page navigation
    (events/on! r :arrow-up :left-click   (fn [_ _ _] (sig/sset-l! current-page (max 0 (dec (sig/sget-l current-page))))))
    (events/on! r :arrow-down :left-click (fn [_ _ _] (sig/sset-l! current-page (min (dec total-pages) (inc (sig/sget-l current-page))))))
    ;; Mouse move (perspective + cursor tracking)
    (events/on! r :root :mouse-scroll
      (fn [_ _ evt] (sig/sset-d! mouse-y (+ (sig/sget-d mouse-y) (* (:delta evt) 20.0)))))
    r))

(defn open! [player]
  (let [r (create-runtime player)]
    (bridge/open-reactive-screen! r "Terminal")))
