(ns cn.li.mcmod.gui.cgui
  "Pure Clojure CGui model and operations."
  (:require [clojure.string :as str]))

(defn- new-widget
  [{:keys [name pos size scale z-level visible? widget-type]
    :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0 visible? true widget-type :widget}}]
  {:id (str (java.util.UUID/randomUUID))
   :widget-type widget-type
   :name (atom name)
   :pos (atom (vec pos))
   :size (atom (vec size))
   :scale (atom scale)
   :z-level (atom z-level)
   :visible? (atom visible?)
   :children (atom [])
   :components (atom [])
   :events (atom {})
   :metadata (atom {})})

(defn- widget? [x]
  (and (map? x) (contains? x :children) (contains? x :components)))

(defn- component-kind [component]
  (or (:kind component) (::kind component) :unknown))


(declare add-widget! copy-widget)

(defn create-widget
  [& {:keys [name pos size scale z-level]
      :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (new-widget {:name name :pos pos :size size :scale scale :z-level z-level :widget-type :widget}))

(defn create-container
  [& {:keys [name pos size scale z-level]
      :or {name nil pos [0 0] size [0 0] scale 1.0 z-level 0}}]
  (new-widget {:name name :pos pos :size size :scale scale :z-level z-level :widget-type :container}))

(defn copy-widget [widget]
  (let [clone (new-widget {:name @(-> widget :name)
                           :pos @(-> widget :pos)
                           :size @(-> widget :size)
                           :scale @(-> widget :scale)
                           :z-level @(-> widget :z-level)
                           :visible? @(-> widget :visible?)
                           :widget-type (:widget-type widget)})]
    (reset! (:components clone)
            (mapv (fn [comp]
                    (if (and (map? comp) (:state comp))
                      (assoc comp :state (atom @(:state comp)))
                      comp))
                  @(:components widget)))
    (doseq [child @(:children widget)]
      (add-widget! clone (copy-widget child)))
    clone))

(defn add-widget!
  [container widget]
  (swap! (:children container) conj widget)
  container)

(defn remove-widget!
  [container widget]
  (swap! (:children container) (fn [xs] (vec (remove #(= (:id %) (:id widget)) xs))))
  container)

(defn clear-widgets!
  [container]
  (reset! (:children container) [])
  container)

(defn get-widgets [container]
  (vec @(:children container)))

(defn get-draw-list [container]
  (get-widgets container))

(defn- find-child-by-name [node child-name]
  (first (filter #(= child-name (or @(:name %) "")) (get-widgets node))))

(defn find-widget
  [root name-or-path]
  (let [parts (str/split (str name-or-path) #"/")]
    (reduce (fn [node part]
              (when (and node (widget? node))
                (find-child-by-name node part)))
            root
            parts)))

(defn set-name!
  [widget name]
  (reset! (:name widget) name)
  widget)

(defn get-name [widget]
  (or @(:name widget) ""))

(defn set-pos!
  [widget x y]
  (reset! (:pos widget) [x y])
  widget)

(defn set-position! [widget x y]
  (set-pos! widget x y))

(defn get-pos [widget]
  @(:pos widget))

(defn set-size!
  [widget w h]
  (reset! (:size widget) [w h])
  widget)

(defn get-size [widget]
  @(:size widget))

(defn get-width [widget]
  (double (first (get-size widget))))

(defn get-height [widget]
  (double (second (get-size widget))))

(defn set-scale!
  [widget scale]
  (reset! (:scale widget) scale)
  widget)

(defn set-w-align!
  "Set horizontal alignment metadata on `widget`.
  Accepts keywords or strings: :left/:center/:right or \"left\"/\"center\"/\"right\".
  Stores value under `:w-align` in widget metadata and returns the widget." 
  [widget align]
  (when widget
    (let [a (if (keyword? align) align (keyword (str/lower-case (str align))))]
      (swap! (:metadata widget) assoc-in [:transform-meta :align-width] a))
    widget))

(defn set-h-align!
  "Set vertical alignment metadata on `widget`.
  Accepts keywords or strings: :top/:middle/:bottom or \"top\"/\"middle\"/\"bottom\".
  Stores value under `:h-align` in widget metadata and returns the widget." 
  [widget align]
  (when widget
    (let [a (if (keyword? align) align (keyword (str/lower-case (str align))))]
      (swap! (:metadata widget) assoc-in [:transform-meta :align-height] a))
    widget))

(defn set-z-level!
  [widget z]
  (reset! (:z-level widget) z)
  widget)

(defn set-visible!
  [widget visible?]
  (reset! (:visible? widget) (boolean visible?))
  widget)

(defn visible? [widget]
  (boolean @(:visible? widget)))

(defn add-widget-component!
  [widget component]
  (swap! (:components widget) conj component)
  widget)

(defn remove-widget-component!
  [widget component]
  (swap! (:components widget)
         (fn [xs] (vec (remove #(= % component) xs))))
  widget)

(defn get-widget-component
  [widget kind]
  (first (filter #(= (component-kind %) kind) @(:components widget))))

(defn get-widget-component-by-class
  [widget component-class]
  (let [s (str component-class)
        ;; `str` on a Class yields strings like "class net.minecraft.foo.Bar".
        s (if (clojure.string/starts-with? s "class ") (subs s 6) s)
        simple (last (clojure.string/split s #"\."))
        kind (keyword (clojure.string/lower-case (or simple "")))]
    (get-widget-component widget kind)))

(defn create-component-instance
  [kind]
  {:kind kind :state (atom {})})

(defn listen-widget-event!
  [widget event-klass-or-key handler]
  (let [event-key (if (keyword? event-klass-or-key)
                    event-klass-or-key
                    (keyword (str/lower-case (str event-klass-or-key))))]
    (swap! (:events widget) update event-key (fnil conj []) handler)
    widget))

(defn unlisten-widget-event!
  [widget event-klass-or-key]
  (let [event-key (if (keyword? event-klass-or-key)
                    event-klass-or-key
                    (keyword (str/lower-case (str event-klass-or-key))))]
    (swap! (:events widget) dissoc event-key)
    widget))

(defn clear-widget-events!
  [widget]
  (reset! (:events widget) {})
  widget)

(defn emit-widget-event!
  [widget event-key event]
  (doseq [handler (get @(:events widget) event-key)]
    (handler event))
  event)

(defn stop-event-propagation!
  [event]
  (if (map? event)
    (assoc event :canceled? true)
    event))

(defn create-cgui []
  (let [root (create-container :name "root" :pos [0 0] :size [0 0])]
    ;; per-cgui runtime state stored in root metadata for platform runtime access
    (swap! (:metadata root) assoc :cgui-focus (atom nil)
                                  :dragging-node (atom nil)
                                  :last-drag-time (atom 0)
                                  :last-start-time (atom 0))
    {:type :cgui :root root}))

(defn get-focus
  "Return the current focused widget for the CGUI root (root is the cgui root widget)."
  [root]
  (when root
    (let [m @(:metadata root)]
      (when-let [a (:cgui-focus m)]
        @a))))

(defn gain-focus!
  "Set focus to `widget` for this cgui root. Emits :gain-focus / :lost-focus events.
   `root` is the cgui root widget (the value returned by `get-root`)."
  [root widget]
  (when root
    (let [m @(:metadata root)
          a (:cgui-focus m)]
      (when a
        (let [old @a]
          (when (and old (not= old widget))
            (swap! (:metadata old) assoc :focused? false)
            (emit-widget-event! old :lost-focus {:new-focus widget}))
          (reset! a widget)
          (when widget
            (swap! (:metadata widget) assoc :focused? true)
            (emit-widget-event! widget :gain-focus {:old-focus old}))
          widget)))))

(defn remove-focus!
  "Clear focus for this cgui root."
  [root]
  (when root
    (let [m @(:metadata root)
          a (:cgui-focus m)
          old (when a @a)]
      (when a
        (reset! a nil))
      (when old
        (swap! (:metadata old) assoc :focused? false)
        (emit-widget-event! old :lost-focus {:new-focus nil}))
      nil)))

(defn get-root [cgui]
  (:root cgui))

(defn cgui-add-widget!
  [cgui widget]
  (add-widget! (get-root cgui) widget)
  cgui)

(defn create-cgui-screen
  [cgui]
  {:type :cgui-screen :cgui cgui})

(defn create-cgui-screen-container
  [cgui container]
  {:type :cgui-screen-container :cgui cgui :minecraft-container container})

(defn progressbar-direction-enum [direction]
  direction)

(defn build-widget-tree
  [spec]
  (let [{:keys [type name pos size scale z-level components children]
         :or {type :widget name nil pos [0 0] size [0 0] scale 1.0 z-level 0}} spec
        widget-fn (if (= type :container) create-container create-widget)
        widget (widget-fn :name name :pos pos :size size :scale scale :z-level z-level)]
    (doseq [component components]
      (add-widget-component! widget component))
    (doseq [child-spec children]
      (add-widget! widget (build-widget-tree child-spec)))
    widget))

(defn widget->map
  [widget]
  {:id (:id widget)
   :name (get-name widget)
   :widget-type (:widget-type widget)
   :pos (get-pos widget)
   :size (get-size widget)
   :visible? (visible? widget)
   :children (mapv widget->map (get-widgets widget))})
