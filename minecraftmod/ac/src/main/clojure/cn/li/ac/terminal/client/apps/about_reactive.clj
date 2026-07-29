(ns cn.li.ac.terminal.client.apps.about-reactive
  "Reactive AcademyCraft About app.

   Preserves the upstream Credits/Donate tabs, credit layout, clipped
   scrolling, active-tab presentation, and clickable donation links."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.i18n :as i18n]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mcmod.ui.node INode]
           [cn.li.mcmod.uipojo.runtime UiRt]
           [java.awt Desktop Desktop$Action]
           [java.net URI]))

(def ^:private font-size 30.0)
(def ^:private viewport-h 540.0)
(def ^:private thumb-min-y 58.0)
(def ^:private thumb-max-y 528.0)
(def ^:private thumb-travel (- thumb-max-y thumb-min-y))

(defn- load-about-data []
  (try
    (some-> (io/resource (str "assets/" modid/MOD-ID "/config/about.edn"))
            slurp
            edn/read-string)
    (catch Throwable e
      (log/warn "Failed to load about.edn" (ex-message e))
      {:credits {:header [] :staff [] :donators []}
       :donation {:links [] :text []}})))

(defn- centered [y text & {:keys [size bold?]}]
  {:x 0.0 :y y :w 620.0 :text text :align :center
   :size (double (or size font-size)) :bold? (boolean bold?)})

(defn- credit-layout [{:keys [header staff donators]}]
  (let [items (transient [])
        y (volatile! (* 2.0 font-size))]
    (doseq [text header]
      (conj! items (centered @y text :bold? true))
      (vswap! y + font-size))
    (vswap! y + (* 2.0 font-size))
    (doseq [[job names] staff]
      (conj! items {:x 0.0 :y @y :w 280.0 :text job :align :right
                    :size font-size :bold? true})
      (doseq [person names]
        (conj! items {:x 340.0 :y @y :w 270.0 :text person :align :left
                      :size font-size})
        (vswap! y + font-size))
      (vswap! y + (* 0.5 font-size)))
    (vswap! y + font-size)
    (conj! items (centered @y "Donators" :bold? true))
    (vswap! y + (* 1.1 font-size))
    (let [hint (or (i18n/translate (str "about." modid/MOD-ID ".donators_info"))
                   "In no particular order")]
      (doseq [line (str/split hint #"\\n")]
        (conj! items (centered @y line :size (* 0.7 font-size)))
        (vswap! y + (* 0.7 font-size))))
    (vswap! y + (* 1.5 font-size))
    (doseq [[idx donor] (map-indexed vector (shuffle donators))]
      (let [col (rem idx 3)]
        (conj! items {:x (+ 30.0 (* col 220.0)) :y @y :w 150.0
                      :text donor :align :left :size (* 0.8 font-size)})
        (when (= col 2)
          (vswap! y + (* 0.8 font-size)))))
    (vswap! y + (* 2.0 font-size))
    (conj! items (centered @y "Thank you for playing!" :bold? true))
    (vswap! y + font-size)
    {:items (persistent! items) :max-y (+ @y 30.0)}))

(defn- donation-layout [{:keys [text links]}]
  (let [items (transient [])
        y (volatile! 100.0)]
    (doseq [[idx line] (map-indexed vector text)]
      (when (= idx 2)
        (doseq [{link-text :text :keys [url]} links]
          (vswap! y + 10.0)
          (conj! items {:x 30.0 :y @y :w 560.0 :text link-text :url url
                        :align :left :size 40.0})
          (vswap! y + 50.0)))
      (let [right? (str/starts-with? line "]")
            line (if right? (subs line 1) line)]
        (conj! items {:x 30.0 :y @y :w 560.0
                      :text line :align (if right? :right :left)
                      :size font-size})
        (vswap! y + font-size)))
    (when (< (count text) 3)
      (doseq [{link-text :text :keys [url]} links]
        (vswap! y + 10.0)
        (conj! items {:x 30.0 :y @y :w 560.0 :text link-text :url url
                      :align :left :size 40.0})
        (vswap! y + 50.0)))
    {:items (persistent! items) :max-y @y}))

(defn- dirty-subtree! [^INode n]
  (.setFlag n node/FLAG-LAYOUT-DIRTY)
  (dotimes [idx (.getChildCount n)]
    (when-let [^INode child (.getChild n idx)]
      (dirty-subtree! child))))

(defn- open-link! [url]
  (try
    (if (and (Desktop/isDesktopSupported)
             (.isSupported (Desktop/getDesktop) Desktop$Action/BROWSE))
      (.browse (Desktop/getDesktop) (URI. url))
      (log/warn "Desktop URL browsing is unavailable:" url))
    (catch Throwable e
      (log/warn "Cannot open URL" url (ex-message e)))))

(defn- build-text-item! [^UiRt r ^INode parent idx item]
  (let [id (keyword (str "about-line-" idx))
        text-spec {:kind :text
                   :props {:id id :x (:x item) :y (:y item) :w (:w item)
                           :h (:size item) :text (:text item)
                           :font-size (:size item)
                           :font (if (:bold? item) :ac-bold :ac-normal)
                           :align (:align item)
                           :color (if (:url item) 0xFF5BB4FF 0xFFFFFFFF)}}]
    (if-let [url (:url item)]
      (let [box-id (keyword (str "about-link-" idx))
            ^INode link-node
            (rt/build-child!
              r
              {:kind :box
               :props {:id box-id :x (:x item) :y (:y item) :w (:w item)
                       :h (:size item) :fill 0x00000000 :hover-tint 0x228ECBFF}
               :children [(assoc-in text-spec [:props :x] 0.0)]}
              parent)]
        (rt/register-event! r (.getIdx link-node) :left-click
                            (fn [_ _ _] (open-link! url))))
      (rt/build-child! r text-spec parent))))

(defn- set-visible! [^UiRt r id visible?]
  (when-let [^INode n (rt/node-by-id r id)]
    (.setVisible n visible?)
    (.setTreeDirty r true)))

(defn- set-tab-style! [^UiRt r tab]
  (doseq [[id active?] [[:btn_credits (= tab :credits)]
                        [:btn_donate (= tab :donate)]]]
    (ui/set-prop! r id :fill (if active? 0x80FFFFFF 0x33FFFFFF))
    (set-visible! r (keyword (str (name id) "_glow")) active?)
    (ui/set-prop! r (keyword (str (name id) "_text")) :color
                  (if active? 0xFF3D3F4B 0xFFFFFFFF))))

(defn- create-view! [^UiRt r state layouts tab]
  (let [layout (get layouts tab)
        ^INode content (rt/node-by-id r :content)
        max-scroll (if (= tab :credits)
                     (max 0.0 (- (:max-y layout) viewport-h -50.0))
                     0.0)]
    (rt/clear-children! r content)
    (doseq [[idx item] (map-indexed vector (:items layout))]
      (build-text-item! r content idx item))
    (swap! state assoc :tab tab :scroll 0.0 :max-scroll max-scroll)
    (.setY content 0.0)
    (dirty-subtree! content)
    (let [^INode thumb (rt/node-by-id r :drag_bar)]
      (.setY thumb thumb-min-y)
      (.setFlag thumb node/FLAG-LAYOUT-DIRTY))
    (set-tab-style! r tab)
    (rt/mark-tree-dirty! r)))

(defn- set-scroll! [^UiRt r state value]
  (let [max-scroll (double (:max-scroll @state))
        scroll (max 0.0 (min max-scroll (double value)))
        progress (if (pos? max-scroll) (/ scroll max-scroll) 0.0)
        ^INode content (rt/node-by-id r :content)
        ^INode thumb (rt/node-by-id r :drag_bar)]
    (swap! state assoc :scroll scroll)
    (.setY content (- scroll))
    (dirty-subtree! content)
    (.setY thumb (+ thumb-min-y (* progress thumb-travel)))
    (.setFlag thumb node/FLAG-LAYOUT-DIRTY)))

(defn create-runtime []
  (let [r (rt/create-runtime)
        _ (rt/build! r (ui-xml/load-spec (modid/namespaced-path "guis/new/about.xml")))
        data (load-about-data)
        layouts {:credits (credit-layout (:credits data))
                 :donate (donation-layout (:donation data))}
        state (atom {:tab :credits :scroll 0.0 :max-scroll 0.0})
        drag-start (atom thumb-min-y)]
    (events/on! r :btn_credits :left-click
                (fn [_ _ _] (create-view! r state layouts :credits)))
    (events/on! r :btn_donate :left-click
                (fn [_ _ _] (create-view! r state layouts :donate)))
    (events/on! r :scroll_area :mouse-scroll
                (fn [_ _ evt]
                  (set-scroll! r state
                               (- (:scroll @state)
                                  (* (double (:delta evt 0.0)) 30.0)))))
    (events/on! r :drag_bar :drag-start
                (fn [_ ^INode n _] (reset! drag-start (.getY n))))
    (events/on! r :drag_bar :drag
                (fn [_ _ evt]
                  (let [thumb-y (max thumb-min-y
                                     (min thumb-max-y
                                          (+ @drag-start (double (:dy evt)))))
                        progress (/ (- thumb-y thumb-min-y) thumb-travel)]
                    (set-scroll! r state (* progress (:max-scroll @state))))))
    (create-view! r state layouts :credits)
    r))

(defn open! []
  (bridge/open-reactive-screen! (create-runtime) "About"))
