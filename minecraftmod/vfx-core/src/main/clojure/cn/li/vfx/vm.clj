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
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]
            [cn.li.mcmod.runtime.seeded-rng :as seeded-rng]))

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

(defn- sample-keyframes
  "Piecewise-linear sample of [[progress value] ...] keyframes.  Curves are
   effect data so the same primitive can drive any first-person animation."
  [curve progress]
  (let [points (if (vector? curve) curve [])
        progress (double (max 0.0 (min 1.0 progress)))]
    (cond
      (empty? points) 0.0
      (= 1 (count points)) (double (or (second (first points)) 0.0))
      (<= progress (double (or (first (first points)) 0.0)))
      (double (or (second (first points)) 0.0))
      :else
      (loop [idx 1]
        (if (>= idx (count points))
          (double (or (second (last points)) 0.0))
          (let [[p1 v1] (nth points (dec idx))
                [p2 v2] (nth points idx)
                p1 (double p1) p2 (double p2)]
            (if (<= progress p2)
              (let [span (max 1.0e-9 (- p2 p1))
                    t (max 0.0 (min 1.0 (/ (- progress p1) span)))]
                (+ (double v1) (* t (- (double v2) (double v1)))))
              (recur (inc idx)))))))))

(defmethod sample-node! :vfx/first-person-motion
  [node ctx]
  (let [{:keys [stage phase-ticks duration-ticks curves]} (resolve-fields node ctx)
        curves (or (get curves stage) {})
        duration (double (max 1.0 (or duration-ticks 1.0)))
        progress (/ (double (max 0.0 (or phase-ticks 0.0))) duration)
        transform (into {}
                        (map (fn [key]
                               [key (sample-keyframes (get curves key) progress)]))
                        [:tx :ty :tz :rot-x :rot-y :rot-z])]
    (emit! ctx :first-person :first-person [transform]
           {:material :presentation-first-person
            :variant :transform})))

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

(defmethod sample-node! :vfx/particle-trail
  [node ctx]
  (let [{:keys [start end spacing radius count-limit life-ticks texture seed
                velocity size alpha fade-in fade-out]} (resolve-fields node ctx)]
    (emit! ctx (stage-of ctx :world-after-translucent) :particle
           [{:start start :end end :spacing spacing :radius radius
             :count-limit count-limit :life-ticks life-ticks :texture texture
             :seed seed :velocity velocity :size size :alpha alpha
             :fade-in fade-in :fade-out fade-out}])))

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
  (let [{:keys [target progress color pulse-period width height corner-length]} (resolve-fields node ctx)
        p (block-point target)]
    (when p
      (let [[x y z] p
            progress (max 0.0 (min 1.0 (double (or progress 0.0))))
            width (double (or width 1.0))
            height (double (or height width))
            depth width
            shrink (* 0.05 (- 1.0 progress))
            min-x (+ (double x) shrink)
            min-y (+ (double y) shrink)
            min-z (+ (double z) shrink)
            max-x (- (+ (double x) width) shrink)
            max-y (- (+ (double y) height) shrink)
            max-z (- (+ (double z) depth) shrink)
            corners [[min-x min-y min-z] [max-x min-y min-z]
                     [max-x max-y min-z] [min-x max-y min-z]
                     [min-x min-y max-z] [max-x min-y max-z]
                     [max-x max-y max-z] [min-x max-y max-z]]
            edges [[0 1] [1 2] [2 3] [3 0]
                   [4 5] [5 6] [6 7] [7 4]
                   [0 4] [1 5] [2 6] [3 7]]
            base (if (and (vector? color) (= 4 (count color))) color
                   [255 255 255 200])
            pulse-period (double (or pulse-period 0.0))
            pulse (if (pos? pulse-period)
                    (+ 0.5 (* 0.5 (Math/sin (* pulse-period
                                               (double (or (:age ctx) 0.0))))))
                    1.0)
            rgba (assoc base 3 (* (double (nth base 3)) pulse))
            corner-length (double (or corner-length 0.0))]
        (emit! ctx (stage-of ctx :world-always-on-top) :line
               (if (pos? corner-length)
                 (let [corner-dirs [[1 1] [-1 1] [-1 -1] [1 -1]
                                    [1 1] [-1 1] [-1 -1] [1 -1]]
                       segment (fn [corner [dx dz] bottom?]
                                 (let [[cx cy cz] (nth corners corner)
                                       vy (if bottom? corner-length (- corner-length))]
                                   [{:start [cx cy cz]
                                     :end [cx (+ cy vy) cz]
                                     :color rgba}
                                    {:start [cx cy cz]
                                     :end [(+ cx (* dx corner-length)) cy cz]
                                     :color rgba}
                                    {:start [cx cy cz]
                                     :end [cx cy (+ cz (* dz corner-length))]
                                     :color rgba}]))]
                   (vec (mapcat (fn [corner]
                                  (segment corner (nth corner-dirs corner)
                                           (< corner 4)))
                                (range 8))))
                 (mapv (fn [[a b]] {:start (nth corners a)
                                    :end (nth corners b)
                                    :color rgba}) edges)))))))

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

(defmethod sample-node! :vfx/charge-slow
  [node ctx]
  (let [{:keys [speed]} (resolve-fields node ctx)]
    (when (number? speed)
      (emit! ctx (stage-of ctx :world-after-translucent) :mesh
             [{:variant :walk-speed :local-walk-speed (double speed)}]))))

(defn- seeded-next-long
  [state]
  (let [state (long state)
        z (unchecked-add state -7046029254386353131)
        z (unchecked-multiply
           (bit-xor z (unsigned-bit-shift-right z 30))
           -4658895280553007687)
        z (unchecked-multiply
           (bit-xor z (unsigned-bit-shift-right z 27))
           -7723592293110705685)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn- seeded-uniform
  [state lo hi]
  (let [state (long state)
        lo (double lo)
        hi (double hi)
        z1 (unchecked-add state -7046029254386353131)
        z2 (unchecked-multiply
            (bit-xor z1 (unsigned-bit-shift-right z1 30))
            -4658895280553007687)
        z3 (unchecked-multiply
            (bit-xor z2 (unsigned-bit-shift-right z2 27))
            -7723592293110705685)
        next (bit-xor z3 (unsigned-bit-shift-right z3 31))
        unit (/ (double (bit-and (unsigned-bit-shift-right next 11)
                                0x1fffffffffffff))
                9007199254740992.0)]
    (+ lo (* (- hi lo) unit))))

(defn- seeded-bounded-int
  [state lo hi]
  (let [state (long state)
        lo (long lo)
        hi (long hi)]
    (if (<= hi lo)
      lo
      (let [z1 (unchecked-add state -7046029254386353131)
            z2 (unchecked-multiply
                (bit-xor z1 (unsigned-bit-shift-right z1 30))
                -4658895280553007687)
            z3 (unchecked-multiply
                (bit-xor z2 (unsigned-bit-shift-right z2 27))
                -7723592293110705685)
            next (bit-xor z3 (unsigned-bit-shift-right z3 31))
            unit (/ (double (bit-and (unsigned-bit-shift-right next 11)
                                    0x1fffffffffffff))
                    9007199254740992.0)]
        (+ lo (long (* unit (double (inc (- hi lo))))))))))

(defmethod sample-node! :vfx/charge-ring
  [node ctx]
  (let [{:keys [center charge-ticks max-charge-ticks points base-radius
                radius-growth pulse-amplitude pulse-frequency outer-color
                core-color punched?]} (resolve-fields node ctx)
        ticks (double (or charge-ticks 0.0))
        max-ticks (max 1.0 (double (or max-charge-ticks 1.0)))
        progress (max 0.0 (min 1.0 (/ ticks max-ticks)))
        pulse (* (double (or pulse-amplitude 0.0))
                 (Math/sin (* (double (or pulse-frequency 0.0)) ticks)))
        radius (+ (double (or base-radius 0.0))
                  (* (double (or radius-growth 0.0)) progress) pulse)
        points (long (max 3 (min 128 (or points 3))))]
    (emit! ctx (stage-of ctx :world-after-translucent) :line
           [{:variant :charge-ring :center center :points points :radius radius
             :progress progress :punched? (boolean punched?)
             :outer-color outer-color :core-color core-color}])))

(defmethod sample-node! :vfx/directional-wave
  [node ctx]
  (let [{:keys [position direction ring-count-min ring-count-max life-ticks
                ring-life-min ring-life-max
                ring-life-jitter ring-offset-step ring-offset-jitter
                ring-size-min ring-size-max time-offset-step
                time-offset-jitter fade-in-ratio full-ratio fade-out-ratio
                growth-ticks initial-scale mid-scale mid-ratio final-scale
                forward-speed texture color seed]}
        (resolve-fields node ctx)
        age (double (or (:age ctx) 0.0))
        life (max 1.0 (double (or life-ticks 1.0)))
        t (max 0.0 (min 1.0 (/ age life)))
        fade-in (max 1.0e-6 (double (or fade-in-ratio 0.2)))
        full-ratio (max 0.0 (min 1.0 (double (or full-ratio 0.8))))
        fade-out (max 1.0e-6 (double (or fade-out-ratio 0.2)))
        rise (min 1.0 (/ t fade-in))
        fade (max 0.0 (min 1.0 (/ (- 1.0 t) fade-out)))
        alpha (* rise fade)
        growth (max 1.0e-6 (double (or growth-ticks 1.0)))
        growth-ratio (max 0.0 (/ age growth))
        size-scale (cond
                     (< growth-ratio fade-in)
                     (+ (double (or initial-scale 0.0))
                        (* (/ growth-ratio fade-in)
                           (- (double (or mid-scale 1.0))
                              (double (or initial-scale 0.0)))))
                     (< growth-ratio (double (or mid-ratio 0.2)))
                     (double (or mid-scale 1.0))
                     :else
                     (min (double (or final-scale 1.0))
                          (+ (double (or mid-scale 1.0))
                             (* (/ (- growth-ratio (double (or mid-ratio 0.2)))
                                   (max 1.0e-6 (- 1.0 (double (or mid-ratio 0.2)))))
                                (- (double (or final-scale 1.0))
                                   (double (or mid-scale 1.0)))))))
        [px py pz] (let [p (block-point position)] (or p [0.0 0.0 0.0]))
        [dx dy dz] (let [d (block-point direction)
                         [x y z] (or d [0.0 0.0 1.0])
                         len (Math/sqrt (+ (* x x) (* y y) (* z z)))]
                     (if (pos? len) [(/ x len) (/ y len) (/ z len)]
                         [0.0 0.0 1.0]))
        count-min (long (max 1 (min 16 (or ring-count-min 1))))
        count-max (long (max count-min (min 16 (or ring-count-max count-min))))
        base-seed (long (or seed 0))
        count (seeded-bounded-int base-seed count-min count-max)
        life-min (long (max 1 (min 256 (or ring-life-min 1))))
        life-max (long (max life-min (min 256 (or ring-life-max life-min))))
        rings (loop [idx 0 state (seeded-next-long base-seed) out (transient [])]
                (if (>= idx count)
                  (persistent! out)
                  (let [s1 (seeded-next-long state)
                        s2 (seeded-next-long s1)
                        s3 (seeded-next-long s2)
                        life-j (seeded-uniform s1
                                               (- (double (or ring-life-jitter 0.0)))
                                               (double (or ring-life-jitter 0.0)))
                        off-j (seeded-uniform s2
                                              (- (double (or ring-offset-jitter 0.0)))
                                              (double (or ring-offset-jitter 0.0)))
                        size (seeded-uniform s3
                                             (double (or ring-size-min 1.0))
                                             (double (or ring-size-max 1.0)))
                        time-j (seeded-bounded-int (seeded-next-long s3)
                                                   (- (long (or time-offset-jitter 0)))
                                                   (long (or time-offset-jitter 0)))
                        ring-life (+ (double (seeded-bounded-int s1 life-min life-max))
                                     life-j)]
                    (let [offset (+ (* idx (double (or ring-offset-step 0.0))) off-j)
                          forward-distance (* age (double (or forward-speed 0.0)))
                          distance (+ offset forward-distance)
                          local-t (/ (- age (double time-j))
                                     (max 1.0 ring-life))
                          ring-rise (min 1.0 (max 0.0 (/ local-t fade-in)))
                          ring-fade (if (< local-t full-ratio)
                                      1.0
                                      (max 0.0 (min 1.0
                                                   (/ (- 1.0 local-t) fade-out))))
                          ring-alpha (* ring-rise ring-fade)
                          real-alpha (min alpha ring-alpha)]
                      (recur (inc idx) (long (seeded-next-long s3))
                             (conj! out {:index idx
                                         :offset offset
                                         :center {:vec3 [(+ px (* dx distance))
                                                         (+ py (* dy distance))
                                                         (+ pz (* dz distance))]}
                                         :size (* size size-scale)
                                         :alpha real-alpha
                                         :life ring-life
                                         :time-offset (+ (* idx (double (or time-offset-step 0.0))) time-j)
                                         :max-alpha alpha}))))))]
    (emit! ctx (stage-of ctx :world-after-translucent) :mesh
           [{:variant :directional-wave :position position :direction direction
             :rings rings :texture texture :color color :age age :life-ticks life}])))

(defn- impact-coordinate [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (map? value) (every? #(number? (get value %)) [:hit-x :hit-y :hit-z]))
    [(double (:hit-x value)) (double (:hit-y value)) (double (:hit-z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else [0.0 0.0 0.0]))

(defmethod sample-node! :vfx/impact-burst
  [node ctx]
  (let [{:keys [origin look-dir target-width target-height surface-hits
                splash-count splash-life-ticks splash-texture-pattern
                splash-frame-count splash-frame-duration-ms splash-size
                spray-textures spray-life-ticks spray-duplicates seed]}
        (resolve-fields node ctx)
        [ox oy oz] (impact-coordinate origin)
        [lx ly lz] (impact-coordinate look-dir)
        width (double (or target-width 0.6))
        height (double (or target-height 1.8))
        count (long (max 0 (min 64 (or splash-count 0))))
        splash-life (long (max 1 (or splash-life-ticks 1)))
        spray-life (long (max 1 (or spray-life-ticks 1)))
        frame-count (long (max 1 (or splash-frame-count 1)))
        frame-duration (long (max 1 (or splash-frame-duration-ms 50)))
        age (long (or (:age ctx) 0))
        frame (mod (long (/ (* age 50) frame-duration)) frame-count)
        seed (long (or seed 0))
        splash-size (double (or splash-size 1.0))
        [splashes _]
        (loop [idx 0 state (long seed) out (transient [])]
          (if (>= idx count)
            [(persistent! out) state]
            (let [s1 (seeded-rng/next-long state)
                  s2 (seeded-rng/next-long s1)
                  s3 (seeded-rng/next-long s2)
                  rx (- (* 2.0 (seeded-rng/unit-double s1)) 1.0)
                  ry (seeded-rng/unit-double s2)
                  rz (- (* 2.0 (seeded-rng/unit-double s3)) 1.0)]
              (recur (inc idx) (long (seeded-rng/next-long s3))
                     (conj! out {:position [(+ ox (* rx width) (* lx 0.2))
                                             (+ oy (* ry height) (* ly 0.2))
                                             (+ oz (* rz width) (* lz 0.2))]
                                  :size (+ splash-size (* 0.4 splash-size
                                                            (seeded-rng/unit-double s1)))
                                  :frame frame
                                  :ttl splash-life
                                  :max-ttl splash-life
                                  :texture-pattern splash-texture-pattern})))))
        hits (if (vector? surface-hits) surface-hits [])
        duplicates (long (max 1 (min 4 (or spray-duplicates 1))))
        texture-map (if (map? spray-textures) spray-textures {})
        textures (if (vector? spray-textures) spray-textures [])
        sprays (loop [remaining (seq hits)
                      hit-index 0
                      out (transient [])]
                 (if-let [hit (first remaining)]
                   (let [[hx hy hz] (impact-coordinate
                                     (or (:position hit) hit))
                         face (:face hit)
                         face-textures (if (contains? #{:up :down} face)
                                         (get texture-map :wall textures)
                                         (get texture-map :ground textures))
                         face-size (if (contains? #{:up :down} face) 1.0 0.8)]
                     (recur (next remaining)
                            (inc hit-index)
                            (reduce (fn [acc dup]
                                      (conj! acc
                                             (let [rstate (seeded-rng/next-long
                                                           (+ seed (* hit-index duplicates) dup))]
                                               {:position [hx hy hz]
                                              :block-position [hx hy hz]
                                              :face face
                                              :size (* face-size
                                                       (+ 1.1 (* 0.3 (seeded-rng/unit-double rstate))))
                                              :rotation
                                              (seeded-rng/uniform
                                               rstate
                                               0.0 360.0)
                                              :offset-u (- (* 0.3 (seeded-rng/unit-double
                                                                   (seeded-rng/next-long rstate))) 0.15)
                                              :offset-v (- (* 0.3 (seeded-rng/unit-double
                                                                   (seeded-rng/next-long
                                                                    (seeded-rng/next-long rstate)))) 0.15)
                                              :texture-id (mod dup 3)
                                              :texture (when (seq face-textures)
                                                         (nth face-textures
                                                              (mod dup (count face-textures))))
                                              :ttl spray-life :max-ttl spray-life}))
                                    out
                                    (range duplicates)))))
                   (persistent! out)))]
    (when (or (seq splashes) (seq sprays))
      (emit! ctx (stage-of ctx :world-after-translucent) :mesh
             [{:variant :impact-burst
               :splashes splashes
               :sprays sprays
               :splash-life-ticks splash-life
               :spray-life-ticks spray-life}]))))

(defn- trajectory-point [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (and (vector? value) (= 3 (count value))) (mapv double value)
    :else [0.0 0.0 0.0]))

(defmethod sample-node! :vfx/trajectory-ribbon
  [node ctx]
  (let [{:keys [origin initial-velocity look-dir lateral-offset
                forward-offset vertical-offset drag gravity dt
                segments width style can-perform?]}
        (resolve-fields node ctx)
        [ox oy oz] (trajectory-point origin)
        [lx ly lz] (trajectory-point look-dir)
        horizontal (Math/sqrt (+ (* lx lx) (* lz lz)))
        safe-horizontal (if (pos? horizontal) horizontal 1.0)
        lateral-offset (double (or lateral-offset 0.0))
        forward-offset (double (or forward-offset 0.0))
        vertical-offset (double (or vertical-offset 0.0))
        sx (+ (* -1.0 lateral-offset (/ lz safe-horizontal))
              (* -1.0 forward-offset lx))
        sy (- vertical-offset (* forward-offset ly))
        sz (+ (* lateral-offset (/ lx safe-horizontal))
              (* -1.0 forward-offset lz))
        [vx0 vy0 vz0] (trajectory-point initial-velocity)
        drag (double (or drag 1.0))
        gravity (double (or gravity 0.0))
        dt (double (or dt 0.02))
        segments (long (max 2 (min 256 (or segments 2))))
        ready? (boolean can-perform?)
        palette (or style {})
        color (or (if ready? (:ready-color palette) (:blocked-color palette))
                  (:color palette) [255 255 255 255])
        height (double (or (:height palette) (or width 0.02)))
        points (loop [idx 0
                      px (double (+ ox sx)) py (double (+ oy sy)) pz (double (+ oz sz))
                      vx (double vx0) vy (double vy0) vz (double vz0)
                      acc (transient [])]
                (if (>= idx segments)
                  (persistent! acc)
                  (let [acc (conj! acc {:position [px py pz]
                                        :color color :height height})
                        vx2 (* vx drag) vy2 (* vy drag) vz2 (* vz drag)
                        px2 (+ px (* vx2 dt)) py2 (+ py (* vy2 dt)) pz2 (+ pz (* vz2 dt))
                        vy3 (- vy2 (* dt gravity))]
                    (recur (inc idx) px2 py2 pz2 vx2 vy3 vz2 acc))))]
    (emit! ctx (stage-of ctx :world-after-translucent) :ribbon
           [{:points points :max-points segments :width width :style style}])))

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
  (let [{:keys [sound-id position volume pitch]} (resolve-fields node ctx)]
    (when sound-id
      (emit! ctx :screen-post :audio
             [{:sound-id sound-id :position position
               :volume volume :pitch pitch}]))))

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
