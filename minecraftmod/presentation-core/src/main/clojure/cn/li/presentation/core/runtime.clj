(ns cn.li.presentation.core.runtime
  "Single-threaded retained Presentation Runtime.

   This namespace intentionally has no dependency on presentation-compiler or
   Minecraft. It owns mount state, host geometry, input routing, reducer
   commits, and effect ordering; layout/paint/hit-testing are delegated to
   the Java engine kernels in cn.li.presentation.core.engine, and this
   namespace's only job is to be that engine's BindResolver -- the one
   place Clojure-shaped state (get-in paths, item-label coercion, color
   packing) meets the engine's plain-Object contract.

   extract-stage! is whole-tree memoized: MemoState.refreshRevs runs every
   frame (O(#bindings)), and if the root's MemoKernel stamp and the layout
   rect are both unchanged since the last extraction, measure/arrange/paint
   are skipped entirely and the previous frame's UiDrawList is returned as
   the same object. A per-subtree (rather than whole-view) skip granularity
   remains a possible follow-up; today one changed binding anywhere in a
   view repaints that whole view, not just the affected subtree."
  (:require [cn.li.presentation.core.nodetable :as nodetable]
            [cn.li.presentation.core.scrollbar :as scrollbar]
            [cn.li.mcmod.runtime.presentation-bridge :as presentation-bridge]
            [clojure.string :as string])
  (:import [cn.li.presentation.core HostGeometry MountHandle]
           [cn.li.presentation.core.engine
            NodeTable LayoutArena LayoutContext LayoutKernel PaintKernel HitKernel HitKernel$Hit
            CmdBuf BindResolver NodeFlags CompositeSpec MemoState MemoKernel]
           [cn.li.mcmod.runtime UiResourceRef UiResourceRef$Kind]
           [cn.li.mcmod.runtime.ui UiOp UiTextMetrics]))

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

(defn- stage-geometry-stale?
  "Cheap pre-check (int compares against the existing HostGeometry's own
   fields, no new HostGeometry constructed) so the O(#mounts) map rebuild
   below only runs when a real-frame width/height change actually happened,
   not on every single extract-stage! call regardless of memoization -- this
   was the dominant remaining allocation source in a 'clean frame', found by
   PresentationRuntimeBenchmark measuring real B/op instead of just identity."
  [mounts stage width height]
  (boolean
   (some (fn [[_ instance]]
           (and (= stage (get-in instance [:host :stage]))
                (let [^HostGeometry g (:geometry instance)]
                  (or (not= (.viewportWidth g) width) (not= (.viewportHeight g) height)))))
         mounts)))

(defn- update-stage-geometry! [^UiRuntime runtime stage frame-context]
  (let [width (:width frame-context)
        height (:height frame-context)]
    (when (and (map? frame-context) (number? width) (number? height)
               (pos? width) (pos? height))
      (let [width (int width) height (int height)]
        (when (stage-geometry-stale? (:mounts (runtime-state runtime)) stage width height)
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
                                                        width
                                                        height
                                                        (.scale ^HostGeometry geometry))]
                                     (assoc result mount
                                            (if (= geometry next-geometry)
                                              instance
                                              (assoc instance :geometry next-geometry))))
                                   (assoc result mount instance)))
                               {} mounts))))))))))

;; ============================== BindResolver ==============================

(defn- state-value [state item path]
  (cond
    (and (vector? path) (= :state (first path))) (get-in state (subvec path 1))
    (and (vector? path) (= :item (first path))) (get-in item (subvec path 1))
    (and (vector? path) (= :parent (first path))) (get-in state (subvec path 1))
    :else path))

(defn- item-label [item]
  (cond
    (nil? item) ""
    (map? item) (str (or (:label item) (:text item) (:name item)
                         (:title item) ""))
    :else (str item)))

(defn- component255 [v default]
  (let [n (double (or v default))]
    (long (if (<= 0.0 n 1.0) (Math/round (* 255.0 n)) (Math/round n)))))

(defn- runtime-rgba
  "Matches the pre-rewrite paint.clj `rgba` helper exactly: an integer is
   used as-is, a map/vector's 0..1 components scale to 0..255."
  [value fallback]
  (cond
    (integer? value) (unchecked-int value)
    (map? value) (unchecked-int
                  (bit-or (bit-shift-left (component255 (:a value) 255) 24)
                          (bit-shift-left (component255 (:r value) 255) 16)
                          (bit-shift-left (component255 (:g value) 255) 8)
                          (component255 (:b value) 255)))
    (vector? value) (let [[r g b a] value]
                      (unchecked-int
                       (bit-or (bit-shift-left (component255 a 255) 24)
                               (bit-shift-left (component255 r 255) 16)
                               (bit-shift-left (component255 g 255) 8)
                               (component255 b 255))))
    :else (unchecked-int fallback)))

(defn- resource-index-for
  "src's own :namespace (or a leading `ns:` in a string form) wins; when
   absent, default-namespace (the mounted view's own view-id namespace --
   see mount!'s :view-id) fills in. Plain {[ns path] idx} maps (tests) stay
   read-only; mount resource tables from make-resource-table intern misses."
  [resource-index default-namespace src]
  (let [lookup (fn [ns path]
                 (let [k [(str ns) (str path)]]
                   (cond
                     (and (map? resource-index) (contains? resource-index :intern!))
                     (or (get @(:index resource-index) k)
                         ((:intern! resource-index) ns path))
                     (map? resource-index)
                     (get resource-index k -1)
                     :else -1)))]
    (cond
      (nil? src) -1
      (map? src)
      (let [ns (or (:namespace src) default-namespace)]
        (if ns (lookup ns (:path src)) -1))
      :else
      (let [[namespace path] (string/split (str src) #":" 2)
            ns (or namespace default-namespace)]
        (if ns (lookup ns (or path (str src))) -1)))))

(defn- make-resource-table
  "Static artifact resources plus a growable dynamic sidecar for composite
   textures (tutorial recipe BGs, tag icons) that only exist at runtime."
  [^objects static-resources]
  (let [static-n (alength static-resources)
        index (atom (into {}
                          (keep (fn [i]
                                  (let [r (aget static-resources (int i))]
                                    (when (instance? UiResourceRef r)
                                      [[(.namespace ^UiResourceRef r)
                                        (.path ^UiResourceRef r)]
                                       (int i)]))))
                          (range static-n)))
        dynamic (atom [])
        intern! (fn [ns path]
                  (let [k [(str ns) (str path)]]
                    (or (get @index k)
                        (let [idx (+ static-n (count @dynamic))
                              ref (UiResourceRef. (str ns) (str path) UiResourceRef$Kind/TEXTURE)]
                          (swap! dynamic conj ref)
                          (swap! index assoc k idx)
                          idx))))]
    {:index index
     :intern! intern!
     :finish (fn []
               (let [dyn @dynamic]
                 (if (zero? (count dyn))
                   static-resources
                   (let [^objects out (make-array UiResourceRef (+ static-n (count dyn)))]
                     (System/arraycopy static-resources 0 out 0 static-n)
                     (dotimes [i (count dyn)]
                       (aset ^objects out (int (+ static-n i)) ^UiResourceRef (nth dyn i)))
                     out))))}))

(defn- composite-spec
  "Mirrors the pre-rewrite paint.clj :composite case exactly (local-
   coordinate offsets, the 14px condition-icon clamp, the desaturated
   0xFF555555 color for an unaccepted condition)."
  [resource-index default-namespace item]
  (when (map? item)
    (let [kind (:kind item)
          ix (float (or (:x item) 0.0)) iy (float (or (:y item) 0.0))
          iw (float (or (:w item) 0.0)) ih (float (or (:h item) 0.0))
          color (runtime-rgba (:rgba item) 0xFFFFFFFF)]
      (case kind
        :quad (CompositeSpec. CompositeSpec/QUAD ix iy iw ih color nil (float 0.0) -1)
        :image (CompositeSpec. CompositeSpec/IMAGE ix iy iw ih color nil (float 0.0)
                               (resource-index-for resource-index default-namespace (:src item)))
        :text (CompositeSpec. CompositeSpec/TEXT ix iy iw ih color (item-label (:text item))
                              (float (or (:font-size item) 8.0)) -1)
        :condition (let [accepted? (boolean (:accepted? item))
                        icon-color (if accepted? color (unchecked-int 0xFF555555))]
                    (CompositeSpec. CompositeSpec/CONDITION ix iy (min 14.0 iw) (min 14.0 ih)
                                    icon-color nil (float 0.0)
                                    (resource-index-for resource-index default-namespace (:icon-path item))))
        :model (CompositeSpec. CompositeSpec/MODEL ix iy iw ih color
                               (str (or (:model-id item) (:src item) "")) (float 0.0) -1)
        nil))))

(defn- build-resolver
  "The engine's only escape hatch into Clojure-shaped data. Every other
   engine class only ever sees plain Objects/Numbers/Strings/Lists this
   function hands it; all path resolution, item-label coercion, and color
   packing lives here, not in Java."
  ^BindResolver [bind-maps resource-index default-namespace state]
  (reify BindResolver
    (attribute [_ node attr item]
      (let [bind-map (nth bind-maps node nil)]
        (case (int attr)
          11 (composite-spec resource-index default-namespace item)
          4 (let [v (state-value state item (:items bind-map))]
              (when (sequential? v) (vec v)))
          1 (when-let [path (:text bind-map)] (item-label (state-value state item path)))
          3 (when-let [path (:rgba bind-map)] (runtime-rgba (state-value state item path) 0xFFFFFFFF))
          (let [path (case (int attr)
                       0 (:visible bind-map) 2 (:value bind-map)
                       5 (:x bind-map) 6 (:y bind-map)
                       7 (:width bind-map) 8 (:height bind-map)
                       9 (:font-size bind-map) 10 (:resource bind-map)
                       nil)]
            (when path (state-value state item path))))))))

;; ============================== mount ==============================

(defn- build-resource-index [artifact]
  (into {}
        (map-indexed (fn [i r] [[(str (:namespace r)) (str (:path r))] i]))
        (:resources artifact)))

(defn- build-key-index [^NodeTable table]
  (into {} (map-indexed (fn [i k] [k i])) (.-nodeKeys table)))

(defn- finish-resources [resource-index]
  (when-let [finish (:finish resource-index)]
    (finish)))

(defn mount!
  [^UiRuntime runtime {:keys [host view-id artifact state reduce run-effect! close!]
                     :or {state {}
                          reduce (fn [state _action _payload]
                                   {:state state :effects [] :event-result :pass})}}]
  (owner-thread! runtime)
  (when-not artifact
    (throw (ex-info "mount! requires a pre-loaded :artifact"
                    {:view-id view-id})))
  (let [id (:next-id (runtime-state runtime))
        handle (MountHandle. (long id))
        table (nodetable/table-for artifact)
        resource-index (make-resource-table (.-resources table))
        instance {:handle handle
                  :host host
                  :view-id view-id
                  :artifact artifact
                  :table table
                  :bind-maps (:node/bind-map artifact)
                  :on-maps (:node/on-map artifact)
                  :scrollbar-maps (or (:node/scrollbar artifact) [])
                  :resource-index resource-index
                  :key-index (build-key-index table)
                  :view-state state
                  :reduce reduce
                  :run-effect! (or run-effect! (fn [_] nil))
                  :close! (or close! (fn [_] nil))
                  :geometry (HostGeometry/identity 0 0)
                  :arena (LayoutArena. 64)
                  :cmdbuf (CmdBuf. 64)
                  :memo (MemoState. (max 1 (long (:binding-count artifact 0))) 1)
                  :bind-scratch (object-array (max 1 (long (:binding-count artifact 0))))
                  :bind-rest-paths (mapv (fn [{:keys [id path]}] [(int id) (subvec path 1)])
                                         (:bindings artifact))
                  :layout-stamp nil
                  :layout-geometry nil
                  :layout-metrics-epoch -1
                  :paint-stamp nil
                  :last-result nil
                  :root-instance -1
                  :commands nil
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

(defn instance!
  "The mount's full internal state map. Public as a read-only introspection
   accessor (tests, debug tooling); callers should treat the result as
   opaque beyond documented keys (:view-state, :geometry, :focus,
   :hover-target, :scroll-offsets) since the rest is engine plumbing."
  [^UiRuntime runtime mount]
  (or (get-in (runtime-state runtime) [:mounts mount])
      (throw (ex-info "unknown Presentation mount" {:mount mount}))))

(defn present!
  [^UiRuntime runtime mount next-state]
  (owner-thread! runtime)
  (instance! runtime mount)
  (vswap! (:state runtime) assoc-in [:mounts mount :view-state] next-state)
  next-state)

(defn update-view! [^UiRuntime runtime mount f & args]
  (let [current (:view-state (instance! runtime mount))]
    (present! runtime mount (apply f current args))))

(defn update-host! [^UiRuntime runtime mount ^HostGeometry geometry]
  (owner-thread! runtime)
  (let [current (:geometry (instance! runtime mount))]
    ;; Real callers (and update-stage-geometry! below) construct a fresh
    ;; HostGeometry every call regardless of whether anything actually
    ;; changed. Keeping the OLD reference when the new one is only
    ;; value-equal is what lets extract-stage!'s identical? geometry check
    ;; -- and therefore whole-tree memoization -- ever hit in practice.
    (when-not (= current geometry)
      (vswap! (:state runtime) assoc-in [:mounts mount :geometry] geometry)))
  geometry)

;; ============================== layout ==============================

(defn- geometry-rect [^HostGeometry geometry]
  {:x (float (.originX geometry))
   :y (float (.originY geometry))
   :width (float (max 1 (.viewportWidth geometry)))
   :height (float (max 1 (.viewportHeight geometry)))})

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

(defn- scroll-offset-array ^floats [key-index scroll-offsets node-count]
  (let [arr (float-array node-count)]
    (doseq [[k v] scroll-offsets]
      (when-let [idx (get key-index k)]
        (aset arr (int idx) (float v))))
    arr))

(defn- resolve-bindings!
  "Refreshes `out` -- a per-mount scratch array allocated once in mount!
   and reused every frame, never allocated fresh here -- with every
   :state-scoped binding's current value. MemoState.refreshRevs only reads
   this array (copying values it hasn't seen before into its own lastVal
   slots); it never retains the array itself, so reuse is safe."
  [^objects out bind-rest-paths state]
  (doseq [[id rest-path] bind-rest-paths]
    (aset out (int id) (get-in state rest-path)))
  out)

(defn- ensure-layout!
  "Unconditionally recomputes measure+arrange from current state. Callers
   that care about cost should go through ensure-layout-current! instead,
   which only calls this when MemoKernel says the committed arena is
   actually stale. Returns the root instance index, or -1 for an empty
   table."
  [instance]
  (let [^NodeTable table (:table instance)
        ^LayoutArena arena (:arena instance)
        resolver (build-resolver (:bind-maps instance) (:resource-index instance)
                                        (some-> instance :view-id namespace) (:view-state instance))
        offsets (scroll-offset-array (:key-index instance) (:scroll-offsets instance) (.-n table))
        ctx (LayoutContext. resolver (presentation-bridge/current-text-metrics) offsets)
        rect (content-rect (:artifact instance) (:geometry instance))
        root (LayoutKernel/expand table arena resolver)]
    (when (>= root 0)
      (LayoutKernel/measure table arena ctx root (:width rect) (:height rect)
                             LayoutKernel/EXACTLY LayoutKernel/EXACTLY)
      (LayoutKernel/arrange table arena ctx root (:x rect) (:y rect) (:width rect) (:height rect) -1))
    root))

(defn- ensure-layout-current!
  "The shared memoization gate for layout: refreshes bindings (always
   O(#bindings), needed to even know whether anything changed), then only
   calls ensure-layout! when MemoKernel says the committed arena is stale.
   dispatch! (hit-testing needs nothing beyond a current arena) and
   extract-stage! (which additionally decides whether to skip repainting)
   both go through this single check, so a pointer move alone never
   triggers a full re-layout when nothing bound has changed -- see
   PresentationRuntimeBenchmark.hoverPointerMove."
  [instance]
  (let [^MemoState memo (:memo instance)
        ^NodeTable table (:table instance)
        ^UiTextMetrics metrics (presentation-bridge/current-text-metrics)
        metrics-epoch (when metrics (.epoch metrics))
        geometry (:geometry instance)
        geometry-unchanged? (identical? geometry (:layout-geometry instance))]
    (resolve-bindings! (:bind-scratch instance) (:bind-rest-paths instance) (:view-state instance))
    (.refreshRevs memo (:bind-scratch instance))
    (when-not geometry-unchanged? (.invalidateGeometry memo))
    (when (and metrics-epoch (not= metrics-epoch (:layout-metrics-epoch instance)))
      (.invalidateMetrics memo))
    (let [scroll-rev (long (hash (:scroll-offsets instance)))
          stamp (when (pos? (.-n table)) (MemoKernel/subtreeStamp memo table 0 scroll-rev))
          fresh? (boolean (and stamp (= stamp (:layout-stamp instance)) geometry-unchanged?))]
      {:stamp stamp :geometry geometry :metrics-epoch metrics-epoch :fresh? fresh?
       :root (if fresh? (:root-instance instance) (ensure-layout! instance))})))

(defn- scroll-extent
  "Sum of the scroll node's children's own main-axis extent (the total
   scrollable content size), read directly from the already-arranged
   arena -- reuses LayoutKernel's own sibling traversal rather than
   re-deriving child order."
  [^NodeTable table ^LayoutArena arena scroll-inst]
  (let [node (aget ^ints (.-nodeOf arena) scroll-inst)
        row? (= 1 (aget ^ints (.-direction table) node))
        end (aget ^ints (.-subtreeEnd arena) scroll-inst)
        meas ^floats (.-meas arena)]
    (loop [c (LayoutKernel/firstChild arena scroll-inst) sum 0.0]
      (if (>= c 0)
        (recur (LayoutKernel/nextSibling arena c end)
               (+ sum (double (aget meas (if row? (* c 2) (inc (* c 2)))))))
        sum))))

(defn- scroll-max-offset [^NodeTable table ^LayoutArena arena scroll-inst]
  (let [content (scroll-extent table arena scroll-inst)
        viewport (float (.h arena scroll-inst))]
    (float (max 0.0 (- content viewport)))))

(defn- node-key [^NodeTable table node]
  (aget ^objects (.-nodeKeys table) node))

(defn- hover-target-from-hit
  "Identity for hover change detection. Collection templates share one node
   key (often nil), so instance index — unique per expanded item — is the
   stable id. Carries :item/:index/:action so leave can reuse the enter
   node's :on :hover handler and item payload (pre-rewrite parity)."
  [^NodeTable table on-maps ^HitKernel$Hit hover]
  (when hover
    (let [node (.node hover)
          on-map (nth on-maps node nil)]
      {:key (node-key table node)
       :instance (.instance hover)
       :node node
       :item (.item hover)
       :index (.itemIndex hover)
       :action (:hover on-map)})))

(defn- hover-payload [target previous hover?]
  (let [src (or target previous)]
    (cond-> {:target (:key target)
             :hover? (boolean hover?)
             :hover-event (if hover? :enter :leave)
             :previous-hover (:key previous)}
      (some? (:item src))
      (assoc :item (:item src) :index (:index src)))))

(defn- event-point
  "Version hosts normally provide mount-local coordinates. Explicit
   :viewport coordinates are accepted for overlays whose origin is nonzero."
  [event geometry]
  (let [rect (geometry-rect geometry)
        x (float (:x event 0.0))
        y (float (:y event 0.0))]
    (if (= :viewport (:space event))
      [x y]
      [(+ x (:x rect)) (+ y (:y rect))])))

;; ============================== dispatch ==============================

(defn- clamp01 [v] (max 0.0 (min 1.0 v)))

(defn- find-instance-for-node
  "First arena instance whose node index matches, or -1."
  [^LayoutArena arena node]
  (let [n (.-n arena)
        node (int node)]
    (loop [i (int 0)]
      (cond
        (>= i n) -1
        (= node (aget ^ints (.-nodeOf arena) i)) i
        :else (recur (unchecked-inc-int i))))))

(defn- apply-scrollbar-thumbs!
  "Move thumb instances to match linked scroll progress (pre-rewrite paint parity)."
  [instance]
  (let [^NodeTable table (:table instance)
        ^LayoutArena arena (:arena instance)
        scrollbar-maps (:scrollbar-maps instance)
        scroll-offsets (:scroll-offsets instance)
        key-index (:key-index instance)]
    (when (seq scrollbar-maps)
      (let [n (.-n arena)]
        (dotimes [inst n]
          (let [node (aget ^ints (.-nodeOf arena) inst)
                sb (nth scrollbar-maps node nil)]
            (when (and (map? sb) (:thumb? sb) (:for sb))
              (let [target (:for sb)
                    scroll-node (get key-index target)
                    scroll-inst (when (some? scroll-node)
                                  (find-instance-for-node arena scroll-node))
                    max-off (if (and scroll-inst (>= scroll-inst 0))
                              (scroll-max-offset table arena scroll-inst)
                              0.0)
                    offset (float (or (get scroll-offsets target) 0.0))
                    local-y (scrollbar/thumb-y sb (scrollbar/progress offset max-off))
                    parent (aget ^ints (.-parentOf arena) inst)
                    parent-y (if (>= parent 0) (.y arena parent) 0.0)]
                (.setRect arena inst (.x arena inst) (float (+ parent-y local-y))
                          (.w arena inst) (.h arena inst))))))))))

(defn- scrollbar-route
  "Click/drag a SCROLLBAR-flagged node into a scroll-offset update for :for."
  [instance ^NodeTable table ^LayoutArena arena hit-node hit px py event-type]
  (let [sb (nth (:scrollbar-maps instance) hit-node nil)
        target (:for sb)
        scroll-node (when target (get (:key-index instance) target))
        scroll-inst (when (some? scroll-node) (find-instance-for-node arena scroll-node))
        max-off (if (and scroll-inst (>= scroll-inst 0))
                  (scroll-max-offset table arena scroll-inst)
                  0.0)
        current (float (or (get (:scroll-offsets instance) target) 0.0))]
    (when (and (map? sb) target)
      (if (= :drag event-type)
        (let [cap (:pointer-capture instance)]
          (when (:scrollbar? cap)
            (let [next-offset (scrollbar/offset-for-drag (:sb cap) (:start-offset cap)
                                                        (:start-py cap) py (:max-off cap))]
              {:action :input/scroll
               :scroll-offsets {(:target cap) next-offset}
               :pointer-capture cap
               :payload {:target (:target cap) :scroll-offset next-offset
                         :progress (scrollbar/progress next-offset (:max-off cap))
                         :scrollbar? true}})))
        (let [rect-y (if hit (.y arena (.instance ^HitKernel$Hit hit)) py)
              next-offset (if (:thumb? sb)
                            current
                            (scrollbar/offset-for-pointer sb rect-y py max-off))]
          {:action :input/scroll
           :scroll-offsets {target next-offset}
           :pointer-capture {:scrollbar? true :sb sb :target target
                             :start-py py :start-offset next-offset :max-off max-off}
           :payload {:target target :scroll-offset next-offset
                     :progress (scrollbar/progress next-offset max-off)
                     :scrollbar? true}})))))

(defn- routed-event [instance event]
  (if (:action event)
    event
    (let [^NodeTable table (:table instance)
          ^LayoutArena arena (:arena instance)
          resolver (build-resolver (:bind-maps instance) (:resource-index instance)
                                        (some-> instance :view-id namespace) (:view-state instance))
          root (:root-instance instance)
          bind-maps (:bind-maps instance)
          on-maps (:on-maps instance)
          focus (:focus instance)
          capture (:pointer-capture instance)]
      (case (:type event)
        :pointer
        (let [[px py] (event-point event (:geometry instance))
              px (float px) py (float py)
              event (assoc event :x px :y py)
              ^HitKernel$Hit hit (when (and (>= root 0) (#{:down :drag} (:event-type event)))
                                   (HitKernel/topmostAt table arena resolver root px py))
              ^HitKernel$Hit hover (when (and (>= root 0) (= :move (:event-type event)))
                                     (HitKernel/topmostAt table arena resolver root px py))
              hit-node (when hit (.node hit))
              previous (:hover-target instance)
              hover-target (hover-target-from-hit table on-maps hover)
              changed? (and (= :move (:event-type event))
                            (not= (:instance hover-target) (:instance previous)))
              hover-action (when changed?
                             (or (:action hover-target) (:action previous)))]
          (cond
            (= :up (:event-type event))
            {:action :input/pointer :pointer-capture nil :payload event}

            ;; mouseDragged is not always delivered (some hosts only get mouseMoved
            ;; while the button is held). Keep scrollbar dragging alive on :move too.
            (and (#{:drag :move} (:event-type event)) (:scrollbar? capture))
            (or (scrollbar-route instance table arena 0 nil px py :drag)
                {:action :input/pointer :payload event})

            (and hit (= UiOp/PROGRESS (aget ^ints (.-op table) hit-node)))
            (let [x (.x arena (.instance hit))
                  w (.w arena (.instance hit))
                  ratio (float (if (pos? w) (clamp01 (/ (- px x) w)) 0.0))
                  on-map (nth on-maps hit-node nil)]
              {:action (or (:change on-map) (:activate on-map) :input/progress)
               :payload {:target (node-key table hit-node)
                        :value ratio :progress ratio :progress-input true}})

            (and hit (.has table hit-node NodeFlags/FOCUSABLE))
            {:focus {:key (node-key table hit-node)
                    :path (:text (nth bind-maps hit-node nil))
                    :on (nth on-maps hit-node nil)}}

            (and hit (.has table hit-node NodeFlags/SCROLLBAR)
                 (= :down (:event-type event)))
            (or (scrollbar-route instance table arena hit-node hit px py :down)
                {:action :input/pointer :payload event})

            hit
            (let [on-map (nth on-maps hit-node nil)]
              {:action (:activate on-map)
               :payload (cond-> {:target (node-key table hit-node)}
                          (some? (.item hit))
                          (assoc :item (.item hit) :index (.itemIndex hit)))})

            (and (= :drag (:event-type event)) (>= root 0))
            (let [scroll-inst (HitKernel/enclosingScrollAt table arena resolver root px py)]
              (if (>= scroll-inst 0)
                (let [node (aget ^ints (.-nodeOf arena) scroll-inst)
                      key (node-key table node)
                      max-off (scroll-max-offset table arena scroll-inst)
                      current (float (or (get (:scroll-offsets instance) key) 0.0))
                      next-offset (float (max 0.0 (min max-off (+ current (* -1.0 (double (or (:drag-y event) 0.0)))))))]
                  {:action :input/scroll
                   :scroll-offsets {key next-offset}
                   :payload (assoc event :target key :scroll-offset next-offset :drag? true)})
                {:action :input/pointer :payload event}))

            changed?
            {:action (or hover-action :input/hover)
             :hover-target hover-target
             :payload (hover-payload hover-target previous (boolean hover))}

            :else {:action :input/pointer :payload event}))

        :focus {:action :input/focus :payload event}

        :key (let [key-code (int (or (:key-code event) -1))
                  submit-action (get-in focus [:on :submit])]
              (cond
                (and (= key-code 257) submit-action)
                {:action submit-action
                 :payload {:value (let [path (:path focus)]
                                    (get-in (:view-state instance)
                                            (if (and (vector? path) (= :state (first path)))
                                              (subvec path 1) path)))}}
                (= key-code 259) {:action :input/backspace :payload event}
                :else {:action :input/key :payload event}))

        :character {:action (or (get-in focus [:on :change]) :input/character) :payload event}

        :scroll
        (let [[px py] (event-point event (:geometry instance))
              px (float px) py (float py)
              scroll-inst (when (>= root 0) (HitKernel/enclosingScrollAt table arena resolver root px py))]
          (if (and scroll-inst (>= scroll-inst 0))
            (let [node (aget ^ints (.-nodeOf arena) scroll-inst)
                  key (node-key table node)
                  max-off (scroll-max-offset table arena scroll-inst)
                  current (float (or (get (:scroll-offsets instance) key) 0.0))
                  delta (float (* -12.0 (double (or (:delta event) 0.0))))
                  next-offset (float (max 0.0 (min max-off (+ current delta))))]
              {:action :input/scroll
               :scroll-offsets {key next-offset}
               :payload (assoc event :target key :scroll-offset next-offset)})
            {:action :input/unknown :payload event}))

        {:action :input/unknown :payload event}))))

(defn- focus-path [focus]
  (when-let [path (:path focus)]
    (if (and (vector? path) (= :state (first path))) (subvec path 1) path)))

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
  (let [instance0 (instance! runtime mount)
        {:keys [root stamp geometry metrics-epoch fresh?]} (ensure-layout-current! instance0)
        _ (when-not fresh?
            (vswap! (:state runtime)
                    (fn [snapshot]
                      (-> snapshot
                          (assoc-in [:mounts mount :root-instance] root)
                          (assoc-in [:mounts mount :layout-stamp] stamp)
                          (assoc-in [:mounts mount :layout-geometry] geometry)
                          (assoc-in [:mounts mount :layout-metrics-epoch] metrics-epoch)))))
        instance (assoc instance0 :root-instance root)
        routed (routed-event instance event)
        {:keys [action payload focus]} routed
        focus (or focus (:focus instance))
        _ (when (contains? routed :focus)
            (vswap! (:state runtime) assoc-in [:mounts mount :focus] focus))
        _ (when (contains? routed :hover-target)
            (vswap! (:state runtime) assoc-in [:mounts mount :hover-target] (:hover-target routed)))
        _ (when (contains? routed :pointer-capture)
            (vswap! (:state runtime) assoc-in [:mounts mount :pointer-capture] (:pointer-capture routed)))
        _ (when (contains? routed :scroll-offsets)
            (vswap! (:state runtime) update-in [:mounts mount :scroll-offsets]
                    (fn [current] (merge (or current {}) (:scroll-offsets routed)))))
        ;; Scrollbar/thumb geometry depends on offsets; force a paint next frame.
        _ (when (contains? routed :scroll-offsets)
            (vswap! (:state runtime) assoc-in [:mounts mount :paint-stamp] nil))
        state-before (:view-state instance)
        state-edited (edit-input-state state-before focus action payload)
        payload (input-payload state-edited focus action payload)
        response ((:reduce instance) state-edited action payload)
        next-state (if (contains? response :state) (:state response) state-edited)
        effects (or (:effects response) [])
        ;; Scrollbar thumbs register no :activate reducer; Minecraft only
        ;; delivers mouseDragged after mouseClicked returned true. Claim the
        ;; press/drag while a scrollbar capture is armed.
        result (let [base (or (:event-result response) :pass)
                     cap (or (:pointer-capture routed)
                             (when (and (= :pointer (:type event))
                                        (#{:drag :move} (:event-type event)))
                               (:pointer-capture instance)))]
                 (if (:scrollbar? cap) :capture-pointer base))]
    (present! runtime mount next-state)
    (doseq [effect effects]
      (try
        ((:run-effect! instance) effect)
        (catch Throwable error
          (binding [*out* *err*]
            (println "Presentation effect failed:" (pr-str effect) error)))))
    result))

;; ============================== extract ==============================

(defn extract-stage!
  "On a clean frame (no binding, scroll, geometry, or font-metrics change
   since the last extraction) this returns the exact same per-mount result
   map as last time, with zero allocation beyond the O(#bindings) scratch-
   array refresh every frame always pays to detect that nothing changed —
   see PresentationRuntimeBenchmark's cleanFrameExtract for the number this
   is held to (refactor plan §13's acceptance target)."
  [^UiRuntime runtime stage frame-context]
  (owner-thread! runtime)
  (update-stage-geometry! runtime stage frame-context)
  (let [instances (->> (:mounts (runtime-state runtime))
                        vals
                        (filter #(= stage (get-in % [:host :stage]))))]
    {:stage stage
     :frame-context frame-context
     :mounts (mapv (fn [instance]
                     (let [{:keys [root stamp geometry metrics-epoch fresh?]} (ensure-layout-current! instance)
                           paint-fresh? (and fresh? (= stamp (:paint-stamp instance)) (:last-result instance))]
                       (when-not fresh?
                         (vswap! (:state runtime)
                                 (fn [snapshot]
                                   (-> snapshot
                                       (assoc-in [:mounts (:handle instance) :root-instance] root)
                                       (assoc-in [:mounts (:handle instance) :layout-stamp] stamp)
                                       (assoc-in [:mounts (:handle instance) :layout-geometry] geometry)
                                       (assoc-in [:mounts (:handle instance) :layout-metrics-epoch] metrics-epoch)))))
                       (if paint-fresh?
                         (:last-result instance)
                         (let [^NodeTable table (:table instance)
                               ^LayoutArena arena (:arena instance)
                               ^CmdBuf cmdbuf (:cmdbuf instance)
                               resolver (build-resolver (:bind-maps instance) (:resource-index instance)
                                        (some-> instance :view-id namespace) (:view-state instance))
                               ctx (LayoutContext. resolver nil nil)
                               _ (apply-scrollbar-thumbs! (assoc instance :root-instance root
                                                                 :arena arena))
                               _ (.reset cmdbuf)
                               _ (when (>= root 0) (PaintKernel/paint table arena ctx cmdbuf root))
                               resources (or (finish-resources (:resource-index instance))
                                             (.-resources table))
                               commands (.finish cmdbuf 0 (.-clipRects arena) resources)
                               result (assoc (select-keys instance [:handle :view-id :geometry])
                                             :commands commands)]
                           (vswap! (:state runtime)
                                   (fn [snapshot]
                                     (-> snapshot
                                         (assoc-in [:mounts (:handle instance) :commands] commands)
                                         (assoc-in [:mounts (:handle instance) :paint-stamp] stamp)
                                         (assoc-in [:mounts (:handle instance) :last-result] result))))
                           result))))
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
