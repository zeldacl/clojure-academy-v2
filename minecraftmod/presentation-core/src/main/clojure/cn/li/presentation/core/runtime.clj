(ns cn.li.presentation.core.runtime
  "Single-threaded retained Presentation Runtime.

   This namespace intentionally has no dependency on presentation-compiler or
   Minecraft. It owns mount state, host geometry, input routing, reducer
   commits, and effect ordering; rendering is supplied by later renderer code."
  (:require [cn.li.presentation.core.artifact :as artifact]
            [cn.li.presentation.core.composition :as composition]
            [cn.li.presentation.core.scrollbar :as scrollbar]
            [clojure.string :as string])
  (:import [cn.li.presentation.core HostGeometry MountHandle]
           [cn.li.mcmod.runtime UiEditCommand UiEditResult
            UiEditCommand$Insert UiEditCommand$Replace
            UiEditCommand$Remove UiEditCommand$Move]))

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

(defn- update-stage-geometry! [^UiRuntime runtime stage frame-context]
  (let [width (:width frame-context)
        height (:height frame-context)]
    (when (and (map? frame-context) (number? width) (number? height)
               (pos? width) (pos? height))
      (vswap! (:state runtime)
              (fn [snapshot]
                (update snapshot :mounts
                        (fn [mounts]
                          (reduce-kv
                           (fn [result mount instance]
                             (if (= stage (get-in instance [:host :stage]))
                               (let [geometry (:geometry instance)
                                     next-geometry (HostGeometry.
                                                    (.originX ^HostGeometry geometry)
                                                    (.originY ^HostGeometry geometry)
                                                    (int width)
                                                    (int height)
                                                    (.scale ^HostGeometry geometry))]
                                 (assoc result mount
                                        (if (= geometry next-geometry)
                                          instance
                                          (-> instance
                                              (assoc :geometry next-geometry)
                                              (update :dirty into #{:layout :paint :semantics})))))
                               (assoc result mount instance)))
                           {} mounts))))))))

(defn- artifact-id [value]
  (if (keyword? value)
    (if-let [ns (namespace value)] (str ns "/" (name value)) (name value))
    (str value)))

(defn- artifact-node->base-node [node path seen]
  (let [raw-key (or (:key node)
                    (str "node/" (string/join "/" (map str path))))
        base-key (artifact-id raw-key)
        key (if (contains? @seen base-key)
              (str base-key "@" (string/join "." (map str path)))
              base-key)
        _ (swap! seen conj base-key)
        children (mapv (fn [[index child]]
                         (artifact-node->base-node child (conj path :child index) seen))
                       (map-indexed vector (or (:children node) [])))
        slots (into {}
                    (map (fn [[slot entries]]
                           [slot (mapv (fn [[index child]]
                                         (artifact-node->base-node
                                          child (conj path :slot slot index) seen))
                                       (map-indexed vector entries))]))
                    (or (:slots node) {}))]
    (cond-> (assoc node :key key
                   :blueprint (artifact-id (or (:blueprint-id node) (:type node)))
                   :children children
                   :slots slots)
      (get-in node [:style :scrollbar :for])
      (update-in [:style :scrollbar :for]
                 #(artifact-id %)))))

(defn- artifact->base-view [artifact]
  (when (= :pui4 (:magic artifact))
    {:root (artifact-node->base-node (:nodes artifact) [:root] (atom #{}))
     :blueprints (into {}
                       (map (fn [[id descriptor]]
                              [(str id)
                               (assoc descriptor
                                      :id (str id)
                                      :template {:key "blueprint-template"
                                                 :blueprint (artifact-id (:primitive descriptor))
                                                 :type (:primitive descriptor)
                                                 :children []})]))
                       (or (:blueprint-catalog artifact) {}))
     :boundaries (or (:boundaries artifact) {})}))

(defn mount!
  [^UiRuntime runtime {:keys [host view-id artifact base-view blueprints boundaries state reduce run-effect! close! paint-fn]
                     :or {state {}
                          reduce (fn [state _action _payload]
                                   {:state state :effects [] :event-result :pass})}}]
  (owner-thread! runtime)
  (let [id (:next-id (runtime-state runtime))
        handle (MountHandle. (long id))
        artifact (or artifact (artifact/load-view view-id))
        base-view (or base-view (:base-view artifact) (artifact->base-view artifact))
        base-view (when base-view
                    (merge base-view
                           (when blueprints {:blueprints blueprints})
                           (when boundaries {:boundaries boundaries})))
        composition (when base-view (composition/base-composition base-view))
        instance {:handle handle
                  :host host
                  :view-id view-id
                  :artifact artifact
                  :view-state state
                  :reduce reduce
                  :run-effect! (or run-effect! (fn [_] nil))
                  :close! (or close! (fn [_] nil))
                  :paint-fn (or paint-fn (fn [_ _ _] []))
                  :composition composition
                  :geometry (HostGeometry/identity 0 0)
                  :dirty #{:structure :layout :paint :semantics}
                  :commands []
                  :focus nil
                  :pointer-capture nil
                  :hover-target nil
                  :scroll-offsets {}}]
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

(defn- java-map [value]
  (if (nil? value) {} (into {} value)))

(defn- edit-command->map [command]
  (cond
    (instance? UiEditCommand$Insert command)
    {:op :insert
     :target-key (.targetKey ^UiEditCommand$Insert command)
     :slot (.slot ^UiEditCommand$Insert command)
     :index (.index ^UiEditCommand$Insert command)
     :blueprint (.blueprint ^UiEditCommand$Insert command)
     :key (.key ^UiEditCommand$Insert command)
     :props (java-map (.props ^UiEditCommand$Insert command))
     :slots (java-map (.slots ^UiEditCommand$Insert command))}

    (instance? UiEditCommand$Replace command)
    {:op :replace
     :target-key (.targetKey ^UiEditCommand$Replace command)
     :blueprint (.blueprint ^UiEditCommand$Replace command)
     :props (java-map (.props ^UiEditCommand$Replace command))
     :slots (java-map (.slots ^UiEditCommand$Replace command))}

    (instance? UiEditCommand$Remove command)
    {:op :remove :target-key (.targetKey ^UiEditCommand$Remove command)}

    (instance? UiEditCommand$Move command)
    {:op :move
     :target-key (.targetKey ^UiEditCommand$Move command)
     :parent-key (.parentKey ^UiEditCommand$Move command)
     :slot (.slot ^UiEditCommand$Move command)
     :index (.index ^UiEditCommand$Move command)}

    :else
    (throw (ex-info "unknown UiEditCommand implementation" {:value (type command)}))))

(defn composition [^UiRuntime runtime mount]
  (when-let [value (:composition (instance! runtime mount))]
    (composition/composition-view value)))

(defn- mark-composition! [^UiRuntime runtime mount next-composition]
  (vswap! (:state runtime)
          (fn [snapshot]
            (-> snapshot
                (assoc-in [:mounts mount :composition] next-composition)
                (update-in [:mounts mount :dirty]
                           into #{:structure :layout :paint :semantics}))))
  (composition/composition-view next-composition))

(defn apply-edit!
  "Apply one neutral UiEditCommand to the mount's ephemeral Composition." 
  [^UiRuntime runtime mount command]
  (owner-thread! runtime)
  (let [instance (instance! runtime mount)
        current (:composition instance)]
    (if-not current
      (UiEditResult/rejected "mount has no editable BaseView" {})
      (let [result (composition/apply-edit! current (edit-command->map command))]
        (if (= :applied (:status result))
          (do
            (mark-composition! runtime mount (:composition result))
            (UiEditResult/applied (long (:revision result))))
          (UiEditResult/rejected (or (:message result) "UI edit rejected")
                                 (or (:details result) {})))))))

(defn undo-edit! [^UiRuntime runtime mount]
  (owner-thread! runtime)
  (if-let [current (:composition (instance! runtime mount))]
    (let [result (composition/undo! current)]
      (case (:status result)
        :applied (do (mark-composition! runtime mount (:composition result))
                     (UiEditResult/applied (long (:revision result))))
        :noop (UiEditResult/noop (long (:revision result)))
        (UiEditResult/rejected (or (:message result) "UI undo rejected")
                               (or (:details result) {}))))
    (UiEditResult/rejected "mount has no editable BaseView" {})))

(defn redo-edit! [^UiRuntime runtime mount]
  (owner-thread! runtime)
  (if-let [current (:composition (instance! runtime mount))]
    (let [result (composition/redo! current)]
      (case (:status result)
        :applied (do (mark-composition! runtime mount (:composition result))
                     (UiEditResult/applied (long (:revision result))))
        :noop (UiEditResult/noop (long (:revision result)))
        (UiEditResult/rejected (or (:message result) "UI redo rejected")
                               (or (:details result) {}))))
    (UiEditResult/rejected "mount has no editable BaseView" {})))

(defn reset-edits! [^UiRuntime runtime mount]
  (owner-thread! runtime)
  (if-let [current (:composition (instance! runtime mount))]
    (let [next (composition/reset-composition! current)]
      (mark-composition! runtime mount next)
      (UiEditResult/applied 0))
    (UiEditResult/rejected "mount has no editable BaseView" {})))

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
  (cond
    (number? value) (float value)
    (= :fill value) (float fallback)
    :else (float fallback)))

(defn- geometry-rect [geometry]
  {:x (float (.originX ^HostGeometry geometry))
   :y (float (.originY ^HostGeometry geometry))
   :width (float (max 1 (.viewportWidth ^HostGeometry geometry)))
   :height (float (max 1 (.viewportHeight ^HostGeometry geometry)))})

(defn- content-rect
  "Center the artifact design box inside host geometry when scale-policy is :fit."
  [artifact geometry]
  (let [host (geometry-rect geometry)
        ah (or (:host artifact) {})
        dw (:design-width ah)
        dh (:design-height ah)]
    (if (and (= :fit (:scale-policy ah)) (number? dw) (number? dh))
      {:x (float (+ (:x host) (/ (- (:width host) dw) 2.0)))
       :y (float (+ (:y host) (/ (- (:height host) dh) 2.0)))
       :width (float dw)
       :height (float dh)}
      host)))

(defn- event-point [event geometry]
  (let [rect (geometry-rect geometry)
        x (float (:x event 0.0))
        y (float (:y event 0.0))]
    ;; Version hosts normally provide mount-local coordinates. Explicit
    ;; :viewport coordinates are accepted for overlays whose origin is nonzero.
    (if (= :viewport (:space event))
      {:x x :y y}
      {:x (+ x (:x rect))
       :y (+ y (:y rect))})))

(defn- node-rect [parent node]
  (let [{px :x py :y pw :width ph :height} parent
        layout (:layout node)]
    {:x (+ px (float (or (:x layout) 0.0)))
     :y (+ py (float (or (:y layout) 0.0)))
     :width (layout-dimension (:width layout) pw)
     :height (layout-dimension (:height layout) ph)}))

(defn- point-in-rect? [{:keys [x y width height]} px py]
  (and (<= x (float px) (+ x width))
       (<= y (float py) (+ y height))))

(defn- child-rects
  "Pack children along row/column by declared main-axis sizes.
   Unspecified sizes share the remaining space equally."
  [rect direction children]
  (let [horizontal? (= :row direction)
        main-size (float (if horizontal? (:width rect) (:height rect)))
        explicit (mapv (fn [child]
                         (let [v (if horizontal?
                                   (get-in child [:layout :width])
                                   (get-in child [:layout :height]))]
                           (when (number? v) (float v))))
                       children)
        known (reduce + 0.0 (keep identity explicit))
        unknown (count (filter nil? explicit))
        fill (if (pos? unknown)
               (float (max 0.0 (/ (- main-size known) unknown)))
               0.0)]
    (loop [remaining (map vector children explicit)
           cursor (float (if horizontal? (:x rect) (:y rect)))
           acc []]
      (if (empty? remaining)
        acc
        (let [[_child size*] (first remaining)
              size (float (or size* fill))
              child-rect (if horizontal?
                           (assoc rect :x cursor :width size)
                           (assoc rect :y cursor :height size))]
          (recur (rest remaining) (float (+ cursor size)) (conj acc child-rect)))))))


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

(defn- visible-value? [value]
  (or (nil? value)
      (and (not (false? value))
           (not (and (string? value) (string/blank? value))))))

(defn- collection-items [env node]
  (let [items (bound-value env node :items)]
    (if (sequential? items) (vec items) [])))

(defn- collection-item-rects [rect node items env]
  (let [template (or (first (:children node)) {:type :text :layout {}})
        direction (if (= :grid (:type node)) :row :column)
        layout (:layout template)
        count* (max 1 (count items))
        fallback (if (= :row direction) (/ (:width rect) count*) (/ (:height rect) count*))
        extent (layout-dimension (if (= :row direction) (:width layout) (:height layout)) fallback)
        offset (float (or (get-in env [:scroll-offsets (:key node)]) 0.0))]
    (mapv (fn [index _item]
            (if (= :row direction)
              (assoc rect :x (+ (:x rect) (* index extent) (- offset))
                           :width extent)
              (assoc rect :y (+ (:y rect) (* index extent) (- offset))
                           :height extent)))
          (range) items)))

(defn- hit-scroll
  ([node parent px py]
   (hit-scroll node parent {:state {}} px py))
  ([node parent env px py]
   (let [rect (node-rect parent node)
         type (:type node)
         visible (bound-value env node :visible)]
     (when (visible-value? visible)
       (or (when (and (= :scroll type) (point-in-rect? rect px py))
             (let [items (collection-items env node)
                   templates (vec (:children node))
                   item-rects (collection-item-rects rect node items env)]
               (some (fn [[item item-rect]]
                       (some #(hit-scroll % item-rect (assoc env :item item) px py)
                             templates))
                     (map vector items item-rects))))
           (when (#{:grid :repeater} type)
             (let [items (collection-items env node)
                   templates (vec (:children node))
                   item-rects (collection-item-rects rect node items env)]
               (some (fn [[item item-rect]]
                       (some #(hit-scroll % item-rect (assoc env :item item) px py)
                             templates))
                     (map vector items item-rects))))
           (when (and (= :scroll type) (point-in-rect? rect px py))
             {:key (:key node) :rect rect
              :max-offset (let [items (collection-items env node)
                                template (or (first (:children node)) {:layout {}})
                                extent (layout-dimension (get-in template [:layout :height])
                                                         (/ (:height rect) (max 1 (count items))))]
                            (float (max 0.0 (- (* extent (count items)) (:height rect)))) )})
           (let [children (:children node)
                 direction (or (get-in node [:layout :direction])
                               (when (= :row type) :row)
                               (when (= :column type) :column))
                 child-rects* (if direction (child-rects rect direction children)
                                (mapv (constantly rect) children))]
             (some (fn [[child child-rect]]
                     (hit-scroll child child-rect env px py))
                   (reverse (map vector children child-rects*)))))))))
(declare hit-action)
(defn- hover-target-key [node env]
  {:id (:id node)
   :key (:key node)
   :index (:index env)})

(defn- hit-hover
  "Return the deepest node declaring `:on :hover` under a pointer."
  ([node parent px py]
   (hit-hover node parent {:state {}} px py))
  ([node parent env px py]
   (let [rect (node-rect parent node)
         type (:type node)
         visible (bound-value env node :visible)]
     (when (visible-value? visible)
       (or (when (and (= :scroll type) (point-in-rect? rect px py))
             (let [items (collection-items env node)
                   templates (vec (:children node))
                   item-rects (collection-item-rects rect node items env)]
               (some (fn [[index item item-rect]]
                       (some (fn [template]
                               (hit-hover template item-rect
                                          (assoc env :item item :index index)
                                          px py))
                             templates))
                     (map vector (range) items item-rects))))
           (when (#{:grid :repeater} type)
             (let [items (collection-items env node)
                   templates (vec (:children node))
                   item-rects (collection-item-rects rect node items env)]
               (some (fn [[index item item-rect]]
                       (some (fn [template]
                               (hit-hover template item-rect
                                          (assoc env :item item :index index)
                                          px py))
                             templates))
                     (map vector (range) items item-rects))))
           (let [children (:children node)
                 direction (or (get-in node [:layout :direction])
                               (when (= :row type) :row)
                               (when (= :column type) :column))
                 child-rects* (if direction (child-rects rect direction children)
                                (mapv (constantly rect) children))]
             (some (fn [[child child-rect]]
                     (hit-hover child child-rect env px py))
                   (reverse (map vector children child-rects*))))
           (when (and (point-in-rect? rect px py)
                      (get-in node [:on :hover]))
             {:target (hover-target-key node env)
              :action (get-in node [:on :hover])
              :payload (cond-> {:target (:key node)}
                         (contains? env :item)
                          (assoc :item (:item env) :index (:index env)))}))))))
(defn- hit-collection [node rect env px py]
  (let [items (collection-items env node)
        templates (if (seq (:children node)) (:children node) [{:type :text :layout {}}])
        item-rects (collection-item-rects rect node items env)]
    (some (fn [[index item item-rect]]
            (some (fn [template]
                    (hit-action template item-rect
                                (assoc env :item item :index index)
                                px py))
                  templates))
          (map vector (range) items item-rects))))
(defn- hit-action
  ([node parent px py]
   (hit-action node parent {:state {}} px py))
  ([node parent env px py]
   (let [rect (node-rect parent node)
         type (:type node)
         visible (bound-value env node :visible)]
     (when (visible-value? visible)
       (or (when (and (= :scroll type) (point-in-rect? rect px py))
           (hit-collection node rect env px py))
         (when (#{:grid :repeater} type)
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
             (= :progress type)
             (let [ratio (if (pos? (:width rect))
                           (max 0.0 (min 1.0 (/ (- (float px) (:x rect)) (:width rect))))
                           0.0)]
               {:action (or (get-in node [:on :change])
                            (get-in node [:on :activate])
                            :input/progress)
                :payload {:target (:key node) :value ratio :progress ratio :progress-input true}})

             (let [sb (scrollbar/spec node)]
               (and sb (:for sb) (not (:thumb? sb))))
             (let [sb (scrollbar/spec node)
                   target (:for sb)
                   scroll-node (scrollbar/find-node (:nodes env) target)
                   max-off (or (scrollbar/max-offset scroll-node env) 0.0)
                   next-offset (scrollbar/offset-for-pointer sb rect py max-off)]
               {:action :input/scroll
                :scroll-offsets {target next-offset}
                :payload {:target target
                          :scroll-offset next-offset
                          :progress (scrollbar/progress next-offset max-off)
                          :progress-input true
                          :scrollbar? true
                          :sb sb
                          :rect rect}})

             (get-in node [:on :activate])
             {:action (get-in node [:on :activate])
              :payload (cond-> {:target (:key node)}
                         (contains? env :item)
                         (assoc :item (:item env) :index (:index env)))}
             :else nil)))))))
(defn- effective-artifact [instance]
  (if-let [current (:composition instance)]
    (assoc (:artifact instance) :nodes (:root current))
    (:artifact instance)))

(defn- routed-event [instance event]
  (if (:action event)
    event
    (let [focus (:focus instance)
          artifact (effective-artifact instance)]
      (case (:type event)
        :pointer (let [point (event-point event (:geometry instance))
                       event (assoc event :x (:x point) :y (:y point))
                       root-rect (content-rect artifact (:geometry instance))
                       hit (when (#{:down :drag} (:event-type event))
                             (hit-action (:nodes artifact) root-rect
                                        {:state (:view-state instance)
                                         :nodes (:nodes artifact)
                                         :scroll-offsets (:scroll-offsets instance)}
                                        (:x event) (:y event)))
                       ;; Keep scrollbar dragging alive even if the pointer leaves the track.
                       hit (or (when (and (= :drag (:event-type event))
                                          (get-in instance [:pointer-capture :scrollbar?]))
                                 (let [cap (:pointer-capture instance)
                                       sb (:sb cap)
                                       target (:target cap)
                                       env {:state (:view-state instance)
                                            :nodes (:nodes artifact)
                                            :scroll-offsets (:scroll-offsets instance)}
                                       scroll-node (scrollbar/find-node (:nodes env) target)
                                       max-off (float (or (:max-off cap)
                                                          (scrollbar/max-offset scroll-node env)
                                                          0.0))
                                       next-offset (scrollbar/offset-for-drag
                                                     sb
                                                     (float (or (:start-offset cap) 0.0))
                                                     (float (or (:start-py cap) (:y event)))
                                                     (:y event)
                                                     max-off)]
                                   {:action :input/scroll
                                    :scroll-offsets {target next-offset}
                                    :pointer-capture cap
                                    :payload {:target target
                                              :scroll-offset next-offset
                                              :progress (scrollbar/progress next-offset max-off)
                                              :progress-input true
                                              :scrollbar? true
                                              :drag? true}}))
                               hit)
                       hover (when (= :move (:event-type event))
                               (hit-hover (:nodes artifact) root-rect
                                          {:state (:view-state instance)
                                           :nodes (:nodes artifact)
                                           :scroll-offsets (:scroll-offsets instance)}
                                          (:x event) (:y event)))
                       drag-target (when (and (= :drag (:event-type event))
                                              (not (get-in hit [:payload :scrollbar?])))
                                    (hit-scroll (:nodes artifact) root-rect
                                               {:state (:view-state instance)
                                                :nodes (:nodes artifact)
                                                :scroll-offsets (:scroll-offsets instance)}
                                               (:x event) (:y event)))
                       drag-key (:key drag-target)
                       drag-current (float (or (get-in instance [:scroll-offsets drag-key]) 0.0))
                       drag-next (float (max 0.0 (min (float (or (:max-offset drag-target) 0.0))
                                                     (+ drag-current (* -1.0 (double (or (:drag-y event) 0.0)))))))
                       previous (:hover-target instance)
                       changed? (and (= :move (:event-type event))
                                     (not= (:target hover) (:target previous)))
                       hover-action (when changed?
                                      (or (:action hover)
                                          (when previous (:action previous))))]
                   (cond
                     (= :up (:event-type event))
                     {:action :input/pointer
                      :pointer-capture nil
                      :payload event}

                     (and (= :drag (:event-type event))
                          (get-in hit [:payload :progress-input]))
                     hit
                     (and (= :drag (:event-type event)) drag-key)
                     {:action :input/scroll
                      :scroll-offsets (assoc (:scroll-offsets instance) drag-key drag-next)
                      :payload (assoc event :target drag-key :scroll-offset drag-next :drag? true)}
                     changed?
                     {:action (or hover-action :input/hover)
                      :hover-target hover
                      :payload (merge (or (:payload hover)
                                          (:payload previous)
                                          {})
                                      {:hover? (boolean hover)
                                       :hover-event (if hover :enter :leave)
                                       :previous-hover (:target previous)})}
                     (get-in hit [:payload :scrollbar?])
                     (let [target (get-in hit [:payload :target])
                           sb (get-in hit [:payload :sb])
                           start-offset (float (or (get-in hit [:payload :scroll-offset])
                                                   (get-in instance [:scroll-offsets target])
                                                   0.0))
                           max-off (float (or (scrollbar/max-offset
                                                (scrollbar/find-node (:nodes artifact) target)
                                                {:state (:view-state instance)
                                                 :nodes (:nodes artifact)})
                                              0.0))]
                       (assoc hit :pointer-capture {:scrollbar? true
                                                    :sb sb
                                                    :rect (get-in hit [:payload :rect])
                                                    :target target
                                                    :start-py (float (:y event))
                                                    :start-offset start-offset
                                                    :max-off max-off}))
                     hit
                     (update hit :payload merge
                                    (cond-> {}
                                      (and (contains? event :button) (not= 0 (:button event)))
                                      (assoc :button (:button event))
                                      (= :viewport (:space event))
                                      (assoc :space :viewport)))
                     :else {:action :input/pointer :payload event}))
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
        :scroll (let [point (event-point event (:geometry instance))
                       root-rect (content-rect artifact (:geometry instance))
                       env {:state (:view-state instance)
                            :nodes (:nodes artifact)
                            :scroll-offsets (:scroll-offsets instance)}
                       target (hit-scroll (:nodes artifact)
                                          root-rect env
                                          (:x point) (:y point))
                       ;; Wheel over the scrollbar track should scroll the linked content.
                       bar (when-not target
                             (let [h (hit-action (:nodes artifact) root-rect env
                                                 (:x point) (:y point))]
                               (when (get-in h [:payload :scrollbar?]) h)))
                       key (or (:key target) (get-in bar [:payload :target]))
                       max-off (float (or (:max-offset target)
                                          (when key
                                            (scrollbar/max-offset
                                              (scrollbar/find-node (:nodes env) key)
                                              env))
                                          0.0))
                       current (float (or (get-in instance [:scroll-offsets key]) 0.0))
                       delta (float (* -12.0 (double (or (:delta event) 0.0))))
                       next-offset (float (max 0.0 (min max-off (+ current delta))))]
                   {:action :input/scroll
                    :scroll-offsets (if key (assoc (:scroll-offsets instance) key next-offset)
                                       (:scroll-offsets instance))
                    :payload (cond-> event
                               key (assoc :target key :scroll-offset next-offset))})
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
        _ (when (contains? routed :hover-target)
            (vswap! (:state runtime) assoc-in [:mounts mount :hover-target]
                    (:hover-target routed)))
        _ (when (contains? routed :pointer-capture)
            (vswap! (:state runtime) assoc-in [:mounts mount :pointer-capture]
                    (:pointer-capture routed)))
        _ (when (contains? routed :scroll-offsets)
            (vswap! (:state runtime) update-in [:mounts mount :scroll-offsets]
                    (fn [current]
                      (merge (or current {}) (:scroll-offsets routed)))))
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
  (update-stage-geometry! runtime stage frame-context)
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
                                            (cond-> (assoc (:view-state instance)
                                                           :presentation/scroll-offsets
                                                           (:scroll-offsets instance))
                                              (:composition instance)
                                              (assoc :presentation/composition
                                                     (composition/composition-view
                                                      (:composition instance))))
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
