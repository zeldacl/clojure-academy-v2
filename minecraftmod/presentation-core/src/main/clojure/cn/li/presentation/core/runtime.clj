(ns cn.li.presentation.core.runtime
  "Single-threaded retained Presentation Runtime.

   This namespace intentionally has no dependency on presentation-compiler or
   Minecraft. It owns mount state, host geometry, input routing, reducer
   commits, and effect ordering; rendering is supplied by later renderer code."
  (:require [cn.li.presentation.core.artifact :as artifact])
  (:import [cn.li.presentation.core HostGeometry MountHandle]))

(defrecord UiRuntime [state owner-thread])

(defn- owner-thread! [^UiRuntime runtime]
  (when-not (identical? (:owner-thread runtime) (Thread/currentThread))
    (throw (IllegalStateException.
            "Presentation Runtime must be accessed from its owner thread"))))

(defn create-runtime
  ([] (create-runtime {}))
  ([{:keys [owner-thread]}]
   (->UiRuntime (volatile! {:next-id 1
                          :mounts {}
                          :resource-epoch 0})
              (or owner-thread (Thread/currentThread)))))

(defn- runtime-state [^UiRuntime runtime] @(:state runtime))

(defn mount!
  [^UiRuntime runtime {:keys [host view-id artifact state reduce run-effect! close! paint-fn]
                     :or {state {}
                          reduce (fn [state _action _payload]
                                   {:state state :effects [] :event-result :pass})}}]
  (owner-thread! runtime)
  (let [id (:next-id (runtime-state runtime))
        handle (MountHandle. (long id))
        artifact (or artifact (artifact/load-view view-id))
        instance {:handle handle
                  :host host
                  :view-id view-id
                  :artifact artifact
                  :view-state state
                  :reduce reduce
                  :run-effect! (or run-effect! (fn [_] nil))
                  :close! (or close! (fn [_] nil))
                  :paint-fn (or paint-fn (fn [_ _ _] []))
                  :geometry (HostGeometry/identity 0 0)
                  :dirty #{:structure :layout :paint :semantics}
                  :commands []
                  :focus nil
                  :pointer-capture nil}]
    (vswap! (:state runtime)
            (fn [snapshot]
              (-> snapshot
                  (update :next-id inc)
                  (assoc-in [:mounts handle] instance))))
    handle))

(defn- instance! [^UiRuntime runtime mount]
  (or (get-in (runtime-state runtime) [:mounts mount])
      (throw (ex-info "unknown Presentation mount" {:mount mount}))))

(defn present!
  "Replace a mount's complete view state and mark dependent phases dirty." 
  [^UiRuntime runtime mount next-state]
  (owner-thread! runtime)
  (instance! runtime mount)
  (vswap! (:state runtime)
          (fn [snapshot]
            (-> snapshot
                (assoc-in [:mounts mount :view-state] next-state)
                (update-in [:mounts mount :dirty]
                           into #{:layout :paint :semantics}))))
  next-state)

(defn update-view! [^UiRuntime runtime mount f & args]
  (let [current (:view-state (instance! runtime mount))]
    (present! runtime mount (apply f current args))))

(defn update-host! [^UiRuntime runtime mount ^HostGeometry geometry]
  (owner-thread! runtime)
  (instance! runtime mount)
  (vswap! (:state runtime)
          (fn [snapshot]
            (-> snapshot
                (assoc-in [:mounts mount :geometry] geometry)
                (update-in [:mounts mount :dirty]
                           into #{:layout :paint :semantics}))))
  geometry)

(defn- layout-dimension [value fallback]
  (if (number? value) (float value) (float fallback)))

(defn- geometry-rect [geometry]
  {:x (float (.originX ^HostGeometry geometry))
   :y (float (.originY ^HostGeometry geometry))
   :width (float (max 1 (.viewportWidth ^HostGeometry geometry)))
   :height (float (max 1 (.viewportHeight ^HostGeometry geometry)))})

(defn- event-point [event geometry]
  (let [rect (geometry-rect geometry)
        x (float (:x event 0.0))
        y (float (:y event 0.0))]
    ;; Version hosts normally provide mount-local coordinates. Explicit
    ;; :viewport coordinates are accepted for overlays whose origin is nonzero.
    (if (= :viewport (:space event))
      {:x x :y y}
      {:x (+ x (:x rect))
       :y (+ y (:y rect))})))(defn- node-rect [parent node]
  (let [{px :x py :y pw :width ph :height} parent
        layout (:layout node)]
    {:x (+ px (float (or (:x layout) 0.0)))
     :y (+ py (float (or (:y layout) 0.0)))
     :width (layout-dimension (:width layout) pw)
     :height (layout-dimension (:height layout) ph)}))

(defn- point-in-rect? [{:keys [x y width height]} px py]
  (and (<= x (float px) (+ x width))
       (<= y (float py) (+ y height))))

(defn- child-rects [rect direction children]
  (let [count* (max 1 (count children))
        horizontal (= :row direction)
        available (if horizontal (:width rect) (:height rect))
        each (/ available count*)]
    (mapv (fn [index child]
            (let [layout (:layout child)]
              (if horizontal
                (assoc rect :x (+ (:x rect) (* index each))
                           :width (layout-dimension (:width layout) each))
                (assoc rect :y (+ (:y rect) (* index each))
                           :height (layout-dimension (:height layout) each)))))
          (range) children)))

(defn- button-id [node]
  (let [key (name (or (:key node) :button))]
    (cond
      (.contains key "left") 0
      (.contains key "right") 1
      :else nil)))

(defn- bound-value [env node key]
  (let [path (get-in node [:bind key])]
    (cond
      (and (vector? path) (= :state (first path)))
      (get-in (:state env) (subvec path 1))
      (and (vector? path) (= :item (first path)))
      (get-in (:item env) (subvec path 1))
      (and (vector? path) (= :parent (first path)))
      (get-in (:parent env) (subvec path 1))
      :else path)))

(defn- collection-items [env node]
  (let [items (bound-value env node :items)]
    (if (sequential? items) (vec items) [])))

(declare hit-action)

(defn- hit-collection [node rect env px py]
  (let [items (collection-items env node)
        templates (vec (:children node))
        template (or (first templates) {:type :text :layout {}})
        direction (if (= :grid (:type node)) :row :column)
        item-rects (child-rects rect direction
                                (mapv (constantly template) items))]
    (some (fn [[index item item-rect]]
            (hit-action template item-rect
                        (assoc env :item item :index index)
                        px py))
          (map vector (range) items item-rects))))

(defn- hit-action
  ([node parent px py]
   (hit-action node parent {:state {}} px py))
  ([node parent env px py]
   (let [rect (node-rect parent node)
         type (:type node)]
     (or (when (#{:scroll :grid :repeater} type)
           (hit-collection node rect env px py))
         (let [children (:children node)
               direction (or (get-in node [:layout :direction])
                             (when (= :row type) :row)
                             (when (= :column type) :column))
               child-rects* (if direction (child-rects rect direction children)
                              (mapv (constantly rect) children))]
           (some (fn [[child child-rect]]
                   (hit-action child child-rect env px py))
                 (reverse (map vector children child-rects*))))
         (when (point-in-rect? rect px py)
           (cond
             (= :button type)
             {:action (get-in node [:on :activate])
              :payload (cond-> {:target (:key node)}
                         (some? (button-id node))
                         (assoc :button-id (button-id node))
                         (contains? env :item)
                         (assoc :item (:item env) :index (:index env)))}

             (= :text-input type)
             {:focus {:key (:key node)
                      :path (get-in node [:bind :text])
                      :field (get-in node [:semantics :field])
                      :on (:on node)}}
             :else nil))))))
(defn- routed-event [instance event]
  (if (:action event)
    event
    (let [focus (:focus instance)]
      (case (:type event)
        :pointer (let [point (event-point event (:geometry instance))
                       event (assoc event :x (:x point) :y (:y point))
                       hit (when (= :down (:event-type event))
                             (hit-action (:nodes (:artifact instance)) (geometry-rect (:geometry instance))
                                        {:state (:view-state instance)}
                                        (:x event) (:y event)))]
                   (if hit
                     (update hit :payload merge
                                    (cond-> {}
                                      (and (contains? event :button) (not= 0 (:button event)))
                                      (assoc :button (:button event))
                                      (= :viewport (:space event))
                                      (assoc :space :viewport)))
                     {:action :input/pointer :payload event}))
        :focus {:action :input/focus :payload event}
        :key (let [key-code (int (or (:key-code event) -1))
                   submit-action (get-in focus [:on :submit])]
               (cond
                 (and (= key-code 257) submit-action)
                 {:action submit-action :payload {:value (let [path (get-in focus [:path])]
                                                       (get-in (:view-state instance)
                                                                (if (and (vector? path) (= :state (first path)))
                                                                  (subvec path 1)
                                                                  path)))}}
                 (= key-code 259) {:action :input/backspace :payload event}
                 :else {:action :input/key :payload event}))
        :character {:action (or (get-in focus [:on :change]) :input/character)
                    :payload event}
        :scroll {:action :input/scroll :payload event}
        {:action :input/unknown :payload event}))))

(defn- focus-path [focus]
  (when-let [path (:path focus)]
    (if (and (vector? path) (= :state (first path)))
      (subvec path 1)
      path)))

(defn- edit-input-state [state focus action payload]
  (if-let [path (focus-path focus)]
    (let [current (str (or (get-in state path) ""))
          next-value (cond
                       (= action :input/backspace) (if (seq current) (subs current 0 (dec (count current))) current)
                       (or (= action :input/character) (contains? payload :text)) (str current (or (:text payload) ""))
                       :else nil)]
      (if (some? next-value) (assoc-in state path next-value) state))
    state))

(defn- input-payload [state focus action payload]
  (if-let [path (focus-path focus)]
    (let [value (str (or (get-in state path) ""))]
      (merge payload {:value value :text value :query value}
             (when-let [field (:field focus)] {:field field})))
    payload))

(defn dispatch!
  "Route one neutral input or explicit action through the pure reducer, then effects."
  [^UiRuntime runtime mount event]
  (owner-thread! runtime)
  (let [instance (instance! runtime mount)
        routed (routed-event instance event)
        {:keys [action payload focus]} routed
        focus (or focus (:focus instance))
        _ (when (contains? routed :focus)
            (vswap! (:state runtime) assoc-in [:mounts mount :focus] focus))
        state-before (:view-state instance)
        state-edited (edit-input-state state-before focus action payload)
        payload (input-payload state-edited focus action payload)
        response ((:reduce instance) state-edited action payload)
        next-state (if (contains? response :state)
                     (:state response)
                     state-edited)
        effects (or (:effects response) [])
        result (or (:event-result response) :pass)]
    (present! runtime mount next-state)
    (doseq [effect effects]
      (try
        ((:run-effect! instance) effect)
        (catch Throwable error
          (binding [*out* *err*]
            (println "Presentation effect failed:" (pr-str effect) error)))))
    result))
(defn extract-stage!
  "Return a stage packet envelope and reuse cached commands when the mount is clean.
   A present!/host update invalidates :paint; the next extraction repaints once and
   clears the dependent dirty flags."
  [^UiRuntime runtime stage frame-context]
  (owner-thread! runtime)
  (let [instances (->> (:mounts (runtime-state runtime))
                        vals
                        (filter #(= stage (get-in % [:host :stage]))))]
    {:stage stage
     :frame-context frame-context
     :mounts (mapv (fn [instance]
                     (let [repaint? (contains? (:dirty instance) :paint)
                           commands (if repaint?
                                      (vec ((:paint-fn instance)
                                            (:artifact instance)
                                            (:view-state instance)
                                            (:geometry instance)))
                                      (:commands instance))
                           dirty (if repaint?
                                   (disj (:dirty instance) :structure :layout :paint :semantics)
                                   (:dirty instance))]
                       (when repaint?
                         (vswap! (:state runtime)
                                 (fn [snapshot]
                                   (-> snapshot
                                       (assoc-in [:mounts (:handle instance) :commands] commands)
                                       (assoc-in [:mounts (:handle instance) :dirty] dirty)))))
                       (assoc (select-keys instance [:handle :view-id :geometry])
                              :dirty dirty
                              :commands commands)))
                   instances)}))
(defn semantics [^UiRuntime runtime mount]
  (get-in (instance! runtime mount) [:artifact :semantics]))

(defn unmount! [^UiRuntime runtime mount]
  (owner-thread! runtime)
  (when-let [instance (get-in (runtime-state runtime) [:mounts mount])]
    ((:close! instance) mount)
    (vswap! (:state runtime) update :mounts dissoc mount))
  nil)

(defn unmount-all! [^UiRuntime runtime]
  (owner-thread! runtime)
  (doseq [mount (keys (:mounts (runtime-state runtime)))]
    (unmount! runtime mount))
  nil)

(defn invalidate-render-resources! [^UiRuntime runtime]
  (owner-thread! runtime)
  (vswap! (:state runtime) update :resource-epoch inc)
  (:resource-epoch (runtime-state runtime)))

