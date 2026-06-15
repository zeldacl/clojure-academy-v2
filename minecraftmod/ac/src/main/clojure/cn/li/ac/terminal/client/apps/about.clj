(ns cn.li.ac.terminal.client.apps.about
  "CLIENT-ONLY: interactive about page ported from original AcademyCraft
  AppAbout / AboutUI.  Loads about.xml layout, renders Credits/Donate tabs
  with scrollable text and clickable donation links."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.xml-parser :as xml-parser]
            [cn.li.mcmod.util.log :as log]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; --- Original AppAbout.java color constants ---

(def ^:private color-text-enable   0xFF3D3F4B)  ;; active tab text
(def ^:private color-text-disable  0xFFFFFFFF)  ;; inactive tab text
(def ^:private color-btn-enable    0x80FFFFFF)  ;; active tab bg (white 50%)
(def ^:private color-btn-disable   0x33FFFFFF)  ;; inactive tab bg (white 20%)
(def ^:private color-link          0xFF5BB4FF)  ;; link blue
(def ^:private color-link-hover    0xFF8ECBFF)  ;; link hover lighter blue
(def ^:private color-text-white    0xFFFFFFFF)
(def ^:private font-size-normal    12.0)
(def ^:private font-size-link      14.0)
(def ^:private font-size-header    14.0)
(def ^:private font-size-donator   10.0)

;; --- Data loading ---

(defn- load-about-data []
  (try
    (let [path (io/resource "assets/my_mod/config/about.edn")]
      (edn/read-string (slurp path)))
    (catch Throwable e
      (log/warn "Failed to load about.edn, using empty data" (ex-message e))
      {:credits {:header [] :staff [] :donators [] :donators-info ""}
       :donation {:links [] :text []}})))

;; --- Tab display ---

(defn- update-tab-display!
  "Update button visuals for active/inactive tab state matching
  original onTabTypeChanged."
  [root active-tab]
  (let [area  (cgui-core/find-widget root "area")]
    (doseq [[btn-name active?]
            [["btn_credits" (= active-tab :credits)]
             ["btn_donate"  (= active-tab :donate)]]]
      (when-let [btn (cgui-core/find-widget area btn-name)]
        ;; glow visibility (active = visible)
        (when-let [glow (cgui-core/find-widget btn "glow")]
          (cgui-core/set-visible! glow active?))
        ;; text color (active = dark gray, inactive = white)
        (when-let [txt (cgui-core/find-widget btn "text")]
          (comp/set-text-color! (comp/get-textbox-component txt)
            (if active? color-text-enable color-text-disable)))
        ;; button bg (active = 50% white, inactive = 20% white)
        (when-let [dt (comp/get-drawtexture-component btn)]
          (swap! dt assoc :color (if active? color-btn-enable color-btn-disable)))))))

;; --- Text item builders ---

(defn- build-credit-items
  "Build a flat list of text items from credits data.
  Each item: {:x :y :text :font-size :align :bold? :url?}"
  [credits-data]
  (let [y    (atom (* 0.7 font-size-normal))
        items (atom [])]
    ;; Header lines (centered, bold)
    (doseq [line (:header credits-data)]
      (swap! items conj {:x 0 :y @y :text line :font-size font-size-header
                         :align :center :bold? true})
      (swap! y + font-size-header))
    (swap! y + font-size-normal)
    ;; Staff sections
    (doseq [[job names] (:staff credits-data)]
      ;; Job title — right aligned, bold
      (swap! items conj {:x -20 :y @y :text job :font-size font-size-normal
                         :align :right :bold? true})
      ;; Names — left aligned
      (doseq [name names]
        (swap! items conj {:x 20 :y @y :text name :font-size font-size-normal
                           :align :left})
        (swap! y + font-size-normal))
      (swap! y + (* 0.5 font-size-normal)))
    (swap! y + font-size-normal)
    ;; "Donators" header
    (swap! items conj {:x 0 :y @y :text "Donators" :font-size font-size-normal
                       :align :center :bold? true})
    (swap! y + font-size-normal)
    ;; Donator info hint
    (when (:donators-info credits-data)
      (swap! items conj {:x 0 :y @y :text (:donators-info credits-data)
                         :font-size (* font-size-normal 0.7) :align :center})
      (swap! y + (* font-size-normal 0.7)))
    (swap! y + (* 0.5 font-size-normal))
    ;; Donator names — 3-column grid
    (let [donators (shuffle (:donators credits-data))
          tw 150.0 margin 30.0]
      (doseq [[i name] (map-indexed vector donators)]
        (let [col (mod i 3)
              x   (+ margin (* col (/ (- 620.0 (* 2 margin) tw) 2)) -310.0)]
          (swap! items conj {:x x :y @y :text name :font-size font-size-donator
                             :align :left}))
        (when (= (mod i 3) 2)
          (swap! y + font-size-donator))))
    (swap! y + font-size-normal)
    ;; "Thank you" line
    (swap! items conj {:x 0 :y @y :text "Thank you for playing!"
                       :font-size font-size-header :align :center :bold? true})
    (swap! y + (* 2 font-size-normal))
    ;; Attach max-y as metadata
    (with-meta @items {:max-y @y})))

(defn- build-donate-items
  "Build text items for Donate tab from donation data."
  [donation-data]
  (let [y    (atom 80.0)
        x    -280.0
        items (atom [])]
    (doseq [line (:text donation-data)]
      (if (str/blank? line)
        (swap! y + 30.0)
        (do
          (swap! items conj {:x x :y @y :text line :font-size font-size-normal
                             :align :left})
          (swap! y + 30.0))))
    ;; Donation links (after text)
    (swap! y + 10.0)
    (doseq [link (:links donation-data)]
      (swap! items conj {:x x :y @y :text (:text link) :font-size font-size-link
                         :align :left :url (:url link)})
      (swap! y + 50.0))
    (with-meta @items {:max-y @y})))

;; --- URL opening ---

(defn- open-url!
  "Open a URL in the system default browser."
  [url]
  (try
    (let [dt (java.awt.Desktop/getDesktop)]
      (when (and (java.awt.Desktop/isDesktopSupported)
                 (.isSupported dt java.awt.Desktop$Action/BROWSE))
        (.browse dt (java.net.URI. url))))
    (catch Throwable e
      (log/warn "Failed to open URL" url (ex-message e)))))

;; --- Scroll area rendering ---

(defn- refresh-scroll-area!
  "Clear and rebuild text widgets in the scroll_area based on current tab
  and drag progress. Credits page scrolls, Donate page is fixed."
  [root current-tab drag-progress data]
  (let [scroll-area (cgui-core/find-widget root "area/scroll_area")]
    (when-not scroll-area
      (log/warn "scroll_area widget not found in about.xml"))
    (when-let [scroll-area (cgui-core/find-widget root "area/scroll_area")]
      (cgui-core/clear-widgets! scroll-area)
      (let [[sw sh] (cgui-core/get-size scroll-area)
            tab-key  @current-tab
            items    (if (= tab-key :credits)
                       (build-credit-items (:credits data))
                       (build-donate-items (:donation data)))
            max-y    (or (:max-y (meta items)) (+ sh 50))
            y-offset (if (= tab-key :credits)
                       (* @drag-progress (max 0.0 (- max-y sh 50.0)))
                       0.0)]
        (doseq [item items
                :let [y (- (:y item) y-offset)]
                :when (and (> y -50) (< y (+ sh 50)))]
          (let [fs  (:font-size item font-size-normal)
                align (:align item :left)
                color (if (:url item) color-link color-text-white)
                w (cgui-core/create-widget
                    :pos [(:x item) y] :size [sw fs])]
            (comp/add-component! w
              (comp/text-box :text (:text item) :font-size fs
                            :color color :align align
                            :font (when (:bold? item) :ac-bold)))
            (when (:url item)
              (events/on-left-click w (fn [_] (open-url! (:url item)))))
            (cgui-core/add-widget! scroll-area w)))))))

;; --- Main UI ---

(defn- build-about-gui []
  (let [xml-path (modid/asset-path "guis" "about.xml")
        doc  (xml-parser/read-xml xml-path)
        root (cgui-core/copy-widget (xml-parser/get-widget doc "main"))
        data (load-about-data)
        current-tab   (atom :credits)
        drag-progress (atom 0.0)]
    ;; Tab button handlers
    (let [area (cgui-core/find-widget root "area")]
      (when-let [btn-credits (cgui-core/find-widget area "btn_credits")]
        (events/on-left-click btn-credits
          (fn [_]
            (reset! current-tab :credits)
            (reset! drag-progress 0.0)
            ;; Reset drag bar position to top
            (when-let [db (cgui-core/find-widget area "drag_bar")]
              (cgui-core/set-pos! db
                (first (cgui-core/get-size area))  ;; right-aligned x
                58.0))
            (update-tab-display! root :credits)
            (refresh-scroll-area! root current-tab drag-progress data))))
      (when-let [btn-donate (cgui-core/find-widget area "btn_donate")]
        (events/on-left-click btn-donate
          (fn [_]
            (reset! current-tab :donate)
            (reset! drag-progress 0.0)
            (when-let [db (cgui-core/find-widget area "drag_bar")]
              (cgui-core/set-pos! db
                (first (cgui-core/get-size area))
                58.0))
            (update-tab-display! root :donate)
            (refresh-scroll-area! root current-tab drag-progress data)))))
    ;; Drag bar — update progress on drag
    (when-let [db-w (cgui-core/find-widget root "area/drag_bar")]
      (events/on-drag db-w
        (fn [evt]
          (let [[_ sy] (cgui-core/get-pos db-w)
                bar-range (- 530.0 58.0)
                new-progress (max 0.0 (min 1.0 (/ (- sy 58.0) bar-range)))]
            (when (not= @drag-progress new-progress)
              (reset! drag-progress new-progress)
              (refresh-scroll-area! root current-tab drag-progress data)))))
      ;; Mouse scroll on drag_bar
      (events/on-mouse-scroll db-w
        (fn [evt]
          (let [delta (* (:delta-y evt) 0.0002)  ;; match original 0.001*0.2
                new-progress (max 0.0 (min 1.0 (- @drag-progress delta)))]
            (when (not= @drag-progress new-progress)
              (reset! drag-progress new-progress)
              (when (= :credits @current-tab)
                (refresh-scroll-area! root current-tab drag-progress data))
              ;; Update drag bar visual position
              (let [bar-y (+ 58.0 (* new-progress (- 530.0 58.0)))]
                (cgui-core/set-pos! db-w
                  (first (cgui-core/get-pos db-w)) bar-y)))))))
    ;; Initial render
    (update-tab-display! root :credits)
    (refresh-scroll-area! root current-tab drag-progress data)
    root))

;; --- Public ---

(defn open-about!
  "Open the interactive about page with Credits/Donate tabs."
  [_player]
  (log/info "Opening about page")
  (client-bridge/open-simple-gui! (build-about-gui) "About"))
