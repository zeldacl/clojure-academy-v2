(ns cn.li.vfx.vm
  "Clojure VFX graph interpreter.

   An effect document's :graph is data, not bytecode -- recipe.clj's
   compile-effect never lowers it into CompiledProgram's opcode/operand
   arrays (register-builtins!'s identity-lower just boxes the whole tree
   into one object constant; ir/encode's arrays carry no real per-node
   type information). This namespace is the actual interpreter: every
   :update/:sample call walks the stored :graph tree fresh against the
   instance's current state, resolving {:ref [:input ...]}/{:ref [:state
   ...]} as it goes and, at sample time, emitting one vfx-contract batch
   per drawable node reached."
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]))

;; ---------------------------------------------------------------------------
;; Value resolution
;; ---------------------------------------------------------------------------

(defn- ref? [value]
  (and (map? value) (vector? (:ref value)) (seq (:ref value))))

(defn resolve-value
  "Resolve {:ref [:input & path]}/{:ref [:state & path]} against ctx's
   :input (accumulated spawn/update signal payload) or :state (the
   instance's own state map); anything else is deep-walked so literal
   vectors/maps of refs (e.g. :layers, :style) resolve too."
  [value ctx]
  (cond
    (ref? value)
    (let [[scope & path] (:ref value)
          root (case scope
                 :input (:input ctx)
                 :state (:state ctx)
                 (throw (ex-info "unknown VFX :ref scope" {:scope scope :ref value})))]
      (get-in root path))
    (map? value) (into {} (map (fn [[k v]] [k (resolve-value v ctx)])) value)
    (vector? value) (mapv #(resolve-value % ctx) value)
    :else value))

(defn- resolve-fields
  "Every data field of `node` (excluding structural keys the dispatcher
   itself walks), resolved against ctx."
  [node ctx]
  (into {} (map (fn [[k v]] [k (resolve-value v ctx)]))
        (dissoc node :component :child :children :cases)))

;; ---------------------------------------------------------------------------
;; Batch emission
;; ---------------------------------------------------------------------------

(defn- apply-alpha [entry ctx]
  (let [alpha-mult (double (get-in ctx [:modifiers :alpha-mult] 1.0))]
    (if (and (not= alpha-mult 1.0) (vector? (:color entry)) (= 4 (count (:color entry))))
      (update entry :color (fn [[r g b a]] [r g b (* (double a) alpha-mult)]))
      entry)))

(defn- apply-scale [entry ctx]
  (let [scale-mult (double (get-in ctx [:modifiers :scale-mult] 1.0))]
    (if (and (not= scale-mult 1.0) (number? (:radius entry)))
      (update entry :radius * scale-mult)
      entry)))

(defn- emit!
  "Push one batch built from `entries` (a vector of per-instance payload
   maps) into ctx's sink. Every entry is alpha/scale-adjusted by whatever
   :vfx/fade or :vfx/scale ancestor is currently in effect."
  [ctx stage primitive entries & [{:keys [material variant sort-mode]}]]
  (let [sink (:sink ctx)
        entries (mapv #(-> % (apply-alpha ctx) (apply-scale ctx)) entries)]
    (when (and sink (seq entries))
      ((:emit! sink) {:stage stage :primitive primitive
                      :material material :variant variant
                      :layout-version 1 :count (count entries)
                      :sort-mode (or sort-mode :stable) :payload entries}))))

;; ---------------------------------------------------------------------------
;; Sample dispatch
;; ---------------------------------------------------------------------------

(defmulti sample-node!
  "Recursively render `node` against ctx (:input :state :age :owner
   :world-id :seed :sink :modifiers). Wrapper nodes recurse into their
   children; leaf nodes call emit!."
  (fn [node ctx] (:component node)))

(defmethod sample-node! :default [node _ctx]
  (throw (ex-info "unknown VFX component" {:component (:component node)})))

;; -- Structural / wrapper nodes ---------------------------------------------

(defmethod sample-node! :vfx/timeline
  [node ctx]
  (let [age (double (or (:age ctx) 0.0))]
    (doseq [{:keys [at node]} (:children node)]
      (when (>= age (double (resolve-value at ctx)))
        (sample-node! node ctx)))))

(defmethod sample-node! :vfx/fade
  [node ctx]
  (let [{:keys [from-tick to-tick from-alpha to-alpha]} (resolve-fields node ctx)
        from-tick (double from-tick) to-tick (double to-tick)
        age (double (or (:age ctx) 0.0))
        span (max 1.0e-6 (- to-tick from-tick))
        t (max 0.0 (min 1.0 (/ (- age from-tick) span)))
        alpha (+ (double from-alpha) (* t (- (double to-alpha) (double from-alpha))))
        prior (double (get-in ctx [:modifiers :alpha-mult] 1.0))]
    (when (and (:child node) (>= age from-tick))
      (sample-node! (:child node)
                    (assoc-in ctx [:modifiers :alpha-mult] (* prior alpha))))))

(defmethod sample-node! :vfx/scale
  [node ctx]
  (let [{:keys [from to]} (resolve-fields node ctx)
        age (double (or (:age ctx) 0.0))
        scale (double (if (>= age 1.0) (or to from) (or from to) ))
        prior (double (get-in ctx [:modifiers :scale-mult] 1.0))]
    (when (:child node)
      (sample-node! (:child node)
                    (assoc-in ctx [:modifiers :scale-mult] (* prior scale))))))

(defmethod sample-node! :vfx/attach
  [node ctx]
  (when (:child node)
    (sample-node! (:child node) ctx)))

(defmethod sample-node! :vfx/first-person-transform
  [node ctx]
  (when (:child node)
    (sample-node! (:child node) (assoc ctx :stage-override :first-person))))

(defmethod sample-node! :vfx/noise
  [node ctx]
  (when (:child node)
    (sample-node! (:child node) ctx)))

(defmethod sample-node! :vfx/event-switch
  [node ctx]
  (when-let [selected (:selected-case ctx)]
    (when-let [child (get (:cases node) selected)]
      (sample-node! child ctx))))

;; -- Drawable leaf nodes ------------------------------------------------

(defn- stage-of [ctx default]
  (or (:stage-override ctx) default))

(defmethod sample-node! :vfx/ring
  [node ctx]
  (let [{:keys [center radius segments color]} (resolve-fields node ctx)
        {:keys [from to]} (or radius {})]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:center center :radius-from (or from radius) :radius-to (or to radius)
             :segments segments :color color}])))

(defmethod sample-node! :vfx/mark-sparks
  [node ctx]
  (let [{:keys [position ttl-ticks count radius color seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:position position :ttl-ticks ttl-ticks :count count
             :radius radius :color color :seed seed}])))

(defmethod sample-node! :vfx/beam
  [node ctx]
  (let [{:keys [start end layers]} (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :beam
           [{:start start :end end :layers layers}])))

(defmethod sample-node! :vfx/ray-beam
  [node ctx]
  (let [{:keys [start end life-ticks grow-ticks style]} (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :beam
           [{:start start :end end :life-ticks life-ticks
             :grow-ticks grow-ticks :style style}])))

(defmethod sample-node! :vfx/ray-fan
  [node ctx]
  (let [{:keys [origin direction length count yaw-range-degrees
                pitch-range-degrees life-ticks grow-ticks style seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:origin origin :direction direction :length length :count count
             :yaw-range-degrees yaw-range-degrees :pitch-range-degrees pitch-range-degrees
             :life-ticks life-ticks :grow-ticks grow-ticks :style style :seed seed}])))

(defmethod sample-node! :vfx/arc-field
  [node ctx]
  (let [{:keys [start end spacing radius count-limit life-ticks seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:start start :end end :spacing spacing :radius radius
             :count-limit count-limit :life-ticks life-ticks :seed seed}])))

(defmethod sample-node! :vfx/arc-strike
  [node ctx]
  (let [{:keys [start end aoe-origin aoe-points arc-life-ticks pattern
                hand-origin? sound-id sound-volume sound-pitch sound-position
                bounds-radius seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:start start :end end :aoe-origin aoe-origin :aoe-points aoe-points
             :arc-life-ticks arc-life-ticks :pattern pattern
             :hand-origin? hand-origin? :bounds-radius bounds-radius :seed seed}])
    (when sound-id
      (emit! ctx :screen-post :audio
             [{:sound-id sound-id :volume sound-volume :pitch sound-pitch
               :position sound-position}]))))

(defmethod sample-node! :vfx/channel-arc
  [node ctx]
  (let [{:keys [mode caster target block-pos block-bounds good?
                charge-ticks visual-max-ticks style seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:mode mode :caster caster :target target :block-pos block-pos
             :block-bounds block-bounds :good? good? :charge-ticks charge-ticks
             :visual-max-ticks visual-max-ticks :style style :seed seed}])))

(defmethod sample-node! :vfx/block-scan
  [node ctx]
  (let [{:keys [origin range max-range filter advanced? life-ticks
                rescan-interval max-results texture base-color tier-colors seed]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-always-on-top) :mesh
           [{:origin origin :range range :max-range max-range :filter filter
             :advanced? advanced? :life-ticks life-ticks :rescan-interval rescan-interval
             :max-results max-results :texture texture :base-color base-color
             :tier-colors tier-colors :seed seed}])))

(defn- block-point [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else nil))

(comment "Draw a bounded, pulsing progress box around a neutral block position.
          Geometry is deliberately emitted as line primitives so renderers can
          choose their own batching/material without coupling VFX Core to a game.
          The node is reusable for any block-target progress indicator.")
(defmethod sample-node! :vfx/block-progress
  [node ctx]
  (let [{:keys [target progress color pulse-period]} (resolve-fields node ctx)
        p (block-point target)]
    (when p
      (let [[x y z] p
            progress (max 0.0 (min 1.0 (double (or progress 0.0))))
            shrink (* 0.05 (- 1.0 progress))
            min-x (+ (double x) shrink)
            min-y (+ (double y) shrink)
            min-z (+ (double z) shrink)
            max-x (- (+ (double x) 1.0) shrink)
            max-y (- (+ (double y) 1.0) shrink)
            max-z (- (+ (double z) 1.0) shrink)
            corners [[min-x min-y min-z] [max-x min-y min-z]
                     [max-x max-y min-z] [min-x max-y min-z]
                     [min-x min-y max-z] [max-x min-y max-z]
                     [max-x max-y max-z] [min-x max-y max-z]]
            edges [[0 1] [1 2] [2 3] [3 0]
                   [4 5] [5 6] [6 7] [7 4]
                   [0 4] [1 5] [2 6] [3 7]]
            base (if (and (vector? color) (= 4 (count color))) color
                   [255 255 255 200])
            pulse (+ 0.5 (* 0.5 (Math/sin (* (double (or pulse-period 0.3))
                                               (double (or (:age ctx) 0.0))))))
            rgba (assoc base 3 (* (double (nth base 3)) pulse))]
        (emit! ctx (stage-of ctx :world-always-on-top) :line
               (mapv (fn [[a b]] {:start (nth corners a)
                                  :end (nth corners b)
                                  :color rgba}) edges))))))

(defmethod sample-node! :vfx/vortex-column
  [node ctx]
  (let [{:keys [base axis height spacing radius count-limit life-ticks
                seed alpha orientation]}
        (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :particle
           [{:base base :axis axis :height height :spacing spacing :radius radius
             :count-limit count-limit :life-ticks life-ticks :seed seed
             :alpha alpha :orientation orientation}])))

(defmethod sample-node! :vfx/ribbon
  [node ctx]
  (let [{:keys [points max-points]} (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :ribbon
           [{:points points :max-points max-points}])))

(defmethod sample-node! :vfx/emitter
  [node ctx]
  (let [{:keys [anchor rate-per-tick limit particle]} (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :particle
           [{:anchor anchor :rate-per-tick rate-per-tick :limit limit
             :particle particle}])))

(defmethod sample-node! :vfx/billboard-sequence
  [node ctx]
  (let [{:keys [anchor texture-pattern frame-count frame-duration-ms half-size]}
        (resolve-fields node ctx)
        age-ms (long (* 50.0 (double (or (:age ctx) 0.0))))
        frame (if (and frame-count (pos? (long frame-count)) frame-duration-ms
                       (pos? (long frame-duration-ms)))
                (mod (long (/ age-ms (long frame-duration-ms))) (long frame-count))
                0)]
    (emit! ctx (stage-of ctx :world-after-translucent) :billboard
           [{:anchor anchor :texture-pattern texture-pattern :frame frame
             :half-size half-size}])))

(defmethod sample-node! :vfx/model-marker
  [node ctx]
  (let [{:keys [anchor texture-pattern frame-count frame-period-ticks parts color facing]}
        (resolve-fields node ctx)
        age (double (or (:age ctx) 0.0))
        frame (if (and frame-count (pos? (long frame-count)) frame-period-ticks
                       (pos? (double frame-period-ticks)))
                (mod (long (/ age (double frame-period-ticks))) (long frame-count))
                0)]
    (emit! ctx (stage-of ctx :world-after-translucent) :mesh
           [{:anchor anchor :texture-pattern texture-pattern :frame frame
             :parts parts :color color :facing facing}])))

(defmethod sample-node! :vfx/camera
  [node ctx]
  (let [{:keys [operation value duration-ticks]} (resolve-fields node ctx)]
    (emit! ctx :screen-post :camera
           [{:operation operation :value value :duration-ticks duration-ticks}])))

(defmethod sample-node! :vfx/audio-one-shot
  [node ctx]
  (let [{:keys [sound-id position]} (resolve-fields node ctx)]
    (when sound-id
      (emit! ctx :screen-post :audio [{:sound-id sound-id :position position}]))))

(defmethod sample-node! :vfx/audio-loop
  [node ctx]
  (let [{:keys [sound-id position volume pitch instance-key stop-on-destroy?]}
        (resolve-fields node ctx)]
    (when sound-id
      (emit! ctx :screen-post :audio
             [{:sound-id sound-id :position position :volume volume :pitch pitch
               :instance-key instance-key :stop-on-destroy? stop-on-destroy?}]))))

;; ---------------------------------------------------------------------------
;; Bounds evaluation (a separate small tree under the effect doc's own
;; :bounds key -- never part of :graph, see recipe.clj/compile-effect,
;; which only expands/validates/compiles :graph).
;; ---------------------------------------------------------------------------

(defn eval-bounds
  "Evaluate an effect document's :bounds sub-tree (currently only
   :vfx/beam-bounds is ever authored there) against ctx, or nil when the
   document declares none -- runtime.clj's visible-instance? already
   treats a nil result as \"always visible\"."
  [bounds-node ctx]
  (when bounds-node
    (case (:component bounds-node)
      :vfx/beam-bounds
      (let [{:keys [start end radius]} (resolve-fields bounds-node ctx)
            radius (double (or radius 0.0))]
        (when (and start end)
          (let [sx (double (or (:x start) (nth start 0 0.0)))
                sy (double (or (:y start) (nth start 1 0.0)))
                sz (double (or (:z start) (nth start 2 0.0)))
                ex (double (or (:x end) (nth end 0 0.0)))
                ey (double (or (:y end) (nth end 1 0.0)))
                ez (double (or (:z end) (nth end 2 0.0)))
                half-length (/ (Math/sqrt (+ (Math/pow (- ex sx) 2.0)
                                             (Math/pow (- ey sy) 2.0)
                                             (Math/pow (- ez sz) 2.0)))
                               2.0)]
            {:center {:x (/ (+ sx ex) 2.0) :y (/ (+ sy ey) 2.0) :z (/ (+ sz ez) 2.0)}
             :radius (+ radius half-length)})))
      (throw (ex-info "unknown VFX :bounds component" {:component (:component bounds-node)})))))

;; ---------------------------------------------------------------------------
;; Lifecycle: :init / :update
;; ---------------------------------------------------------------------------

(def ^:private ticks-per-second 20.0)

(defn- root-lifespan-ticks
  "The instance's own natural lifespan in ticks, or nil to persist until an
   explicit :destroy signal. Taken from :vfx/timeline's :duration-ticks or,
   for graphs with no timeline wrapper (see e.g. ray_fan_transient.edn), the
   leaf root's own :life-ticks -- :session effects have neither and so
   never self-expire. Almost always {:ref [:input :duration-ticks]}
   (billboard_session.edn, thunder-clap's charge VFX, ...) rather than a
   literal, so it must be resolved against the current :input, not just
   number?-checked as-is."
  [graph ctx]
  (let [raw (or (:duration-ticks graph) (:life-ticks graph))]
    (when raw
      (let [value (resolve-value raw ctx)]
        (when (number? value) (double value))))))

(defn init-state
  "Build a new instance's initial state map from an effect's :state-slots
   declaration plus the spawn context (:seed and :params, already merged
   into a single :input map)."
  [context]
  {:age 0.0 :seed (long (or (:seed context) 0)) :input (or (:params context) {})})

(defn advance-state
  "One :update call: merge every :spawn/:update event's payload into
   :input, advance :age by this tick's delta, and end the instance (return
   nil) once the graph's own declared lifespan has elapsed."
  [graph state {:keys [events delta-seconds]}]
  (let [merged-input (reduce (fn [input {:keys [event payload]}]
                               (if (#{:spawn :update} event)
                                 (merge input payload)
                                 input))
                             (:input state) events)
        next-age (+ (double (:age state)) (* (double (or delta-seconds 0.0)) ticks-per-second))
        lifespan (root-lifespan-ticks graph {:input merged-input :state state})]
    (when-not (and lifespan (>= next-age lifespan))
      (assoc state :age next-age :input merged-input))))

(defn sample!
  "One :sample call: walk `graph` against instance state/sample-context,
   emitting batches into sink."
  [graph state {:keys [instance sink] :as sample-context}]
  (sample-node! graph
               {:input (:input state) :state state :age (:age state)
                :owner (:owner instance) :world-id (:world-id instance)
                :seed (:seed state) :sink sink :modifiers {}
                :interpolated-state (:interpolated-state sample-context)}))
