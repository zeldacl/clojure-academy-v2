(ns cn.li.node.expr
  "Self-contained expression language shared by every node-core VM: math,
   vec3, boolean, collection, and random. No Minecraft or mcmod dependency
   -- this is the language layer (see NODE_LANGUAGE.md), not a domain
   vocabulary. Domain-specific opcodes (e.g. combat's ballistic vec3/launch,
   vfx's domain opcodes are supplied through the immutable NodeEnvironment,
   so node-core itself never needs to know what combat or vfx are for."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; SplitMix64 -- the one canonical seeded RNG for the whole node language.
;; Deterministic per (seed, call-index): two clients executing the same
;; compiled program with the same activation seed must see the same random
;; outcomes, and reusing one raw seed across many calls in one activation
;; would make every random/* op in that activation return the same value
;; (a real bug fixed once already in combat-core's predecessor of this
;; file -- callers MUST vary seed per call via next-seed).
;;
;; These three constants' top bit is set, so their 64-bit pattern is a
;; negative signed long. The Clojure reader parses a positive hex literal
;; like 0x9E3779B97F4A7C15 as an arbitrary-precision BigInteger when it
;; exceeds Long/MAX_VALUE, and (long ...) on that throws ("Value out of
;; range for long") rather than reinterpreting the bit pattern the way a
;; Java `long` hex literal would. Long/parseUnsignedLong reads the same 16
;; hex digits and correctly reinterprets them as the intended bit pattern.
(def ^:const ^long golden-gamma (Long/parseUnsignedLong "9E3779B97F4A7C15" 16))
(def ^:const ^long mix-const-1 (Long/parseUnsignedLong "BF58476D1CE4E5B9" 16))
(def ^:const ^long mix-const-2 (Long/parseUnsignedLong "94D049BB133111EB" 16))

(defn next-seed
  ^long [^long seed]
  (unchecked-add seed golden-gamma))

(defn- mix64
  ^long [^long z0]
  (let [z1 (unchecked-multiply (bit-xor z0 (unsigned-bit-shift-right z0 30)) mix-const-1)
        z2 (unchecked-multiply (bit-xor z1 (unsigned-bit-shift-right z1 27)) mix-const-2)]
    (bit-xor z2 (unsigned-bit-shift-right z2 31))))

(defn unit-double
  "A deterministic pseudo-random double in [0,1) for `seed`."
  ^double [^long seed]
  (let [bits (unsigned-bit-shift-right (mix64 seed) 11)]
    (/ (double bits) (double (bit-shift-left 1 53)))))

(defn uniform ^double [^long seed ^double lo ^double hi]
  (+ lo (* (unit-double seed) (- hi lo))))

(defn bounded-int ^long [^long seed ^long lo ^long hi]
  (let [span (max 1 (inc (- hi lo)))]
    (+ lo (long (Math/floor (* (unit-double seed) (double span)))))))

(defn vec3-components
  "Accepts every vec3 shape in the final language: {:vec3 [x y z]}, a plain
   [x y z] vector, or {:x :y :z} (the shape carried by neutral host query
   results). Public so domain expression extensions share one shape dispatch
   instead of re-implementing it."
  [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (vector? value) value
    (and (map? value) (every? #(number? (get value %)) [:x :y :z]))
    [(:x value) (:y value) (:z value)]
    :else (throw (ex-info "expected vec3 expression value" {:value value}))))

(defn- approach-component
  ^double [^double from ^double to ^double step]
  (let [delta (- to from)]
    (if (<= (Math/abs delta) step) to (+ from (if (neg? delta) (- step) step)))))

(defn evaluate
  "Evaluate one expression opcode against already-resolved args (a vector).
   `seed` seeds the deterministic RNG ops -- vary it per call (see
   next-seed) or every random/* op in one program evaluation returns the
   same value."
  ([opcode args] (evaluate opcode args 0 {}))
  ([opcode args seed] (evaluate opcode args seed {}))
  ([opcode args seed extra-ops]
   (let [seed (long seed)]
     (case opcode
       :math/add (double (+ (double (nth args 0)) (double (nth args 1))))
       :math/sub (double (- (double (nth args 0)) (double (nth args 1))))
       :math/mul (double (* (double (nth args 0)) (double (nth args 1))))
       :math/div (let [d (double (nth args 1))]
                   (if (zero? d) 0.0 (double (/ (double (nth args 0)) d))))
       :math/min (double (min (double (nth args 0)) (double (nth args 1))))
       :math/max (double (max (double (nth args 0)) (double (nth args 1))))
       :math/abs (double (Math/abs (double (nth args 0))))
       :math/floor (double (Math/floor (double (nth args 0))))
       ;; A curve-evaluated tick count (math/lerp -> math/floor, mine_detect
       ;; .edn's :cooldown-ticks-next, S6) is a :double by construction
       ;; (every :math/* op is), but :cooldown/start's :ticks param is
       ;; :long -- and cn.li.node.types/assignable? deliberately disallows
       ;; :double -> :long narrowing (a real authoring-bug guard, not
       ;; something to route around with a general unsafe cast). This is
       ;; the one legitimate "I know this is a whole number" case: floor
       ;; and truncate to :long in a single named op, not a generic
       ;; double->long escape hatch that could paper over a real bug
       ;; elsewhere.
       :math/floor-long (long (Math/floor (double (nth args 0))))
       :math/sqrt (double (Math/sqrt (double (nth args 0))))
       :math/pow (double (Math/pow (double (nth args 0)) (double (nth args 1))))
       :math/sin (double (Math/sin (double (nth args 0))))
       :math/cos (double (Math/cos (double (nth args 0))))
       :math/clamp (let [v (double (nth args 0)) lo (double (nth args 1)) hi (double (nth args 2))]
                     (max lo (min hi v)))
       :math/lerp (let [lo (double (nth args 0)) hi (double (nth args 1)) t (double (nth args 2))]
                    (+ lo (* t (- hi lo))))
       :math/lt (< (double (nth args 0)) (double (nth args 1)))
       :math/lte (<= (double (nth args 0)) (double (nth args 1)))
       :math/eq (= (double (nth args 0)) (double (nth args 1)))
       :math/gte (>= (double (nth args 0)) (double (nth args 1)))
       :math/gt (> (double (nth args 0)) (double (nth args 1)))
       :math/select (if (boolean (nth args 0)) (nth args 1) (nth args 2))

       ;; :long/* -- a real ability's tick-count arithmetic (delay-ticks =
       ;; some-tick-count - 2, S6's electron_bomb.edn) cannot go through
       ;; :math/* : those are hard-coded to double, and cn.li.node.types/
       ;; assignable? deliberately disallows :double -> :long narrowing
       ;; (a fractional literal into a :long param is a real authoring
       ;; bug, not implicit narrowing -- see that function's own
       ;; docstring), so a :math/sub result could never satisfy a :long-
       ;; typed param like :projectile/schedule-beam's :delay-ticks. Long-
       ;; typed values need their own arithmetic family, not a cast.
       :long/add (+ (long (nth args 0)) (long (nth args 1)))
       :long/sub (- (long (nth args 0)) (long (nth args 1)))
       :long/mul (* (long (nth args 0)) (long (nth args 1)))
       :long/min (min (long (nth args 0)) (long (nth args 1)))
       :long/max (max (long (nth args 0)) (long (nth args 1)))

       ;; A {:curve :pair} tunable's runtime value is a 2-element vector
       ;; [lo hi] (mine_detect.edn's :cooldown-endpoints, S6) -- the only
       ;; existing index-into-a-vector op is :collection/nth, which is
       ;; deliberately hidden from DSL authors (compiler-internal, only
       ;; for `each`'s own desugaring -- see cn.li.node.ops's docstring).
       ;; A small dedicated pair accessor keeps that boundary intact
       ;; instead of exposing the general nth escape hatch to authors.
       :pair/first (double (nth (nth args 0) 0))
       :pair/second (double (nth (nth args 0) 1))
       ;; A real content inconsistency, not a new curve shape: directed_
       ;; blastwave.edn's :hardness-caps tunable is declared {:curve
       ;; :pair} but real usage indexes a THIRD element (S6) -- rather
       ;; than generalize to an author-facing nth (the same escape hatch
       ;; :pair/first,second were added specifically to avoid exposing),
       ;; one more named accessor for this one documented case.
       :pair/third (double (nth (nth args 0) 2))

       :value/eq (= (nth args 0) (nth args 1))

       ;; body_intensify.edn's (S6) :effect-available-effects tunable is a
       ;; list of "name:max-amplifier" strings (e.g. "jump-boost:1"),
       ;; confirmed by mcmod/runtime/expression-catalog.clj's own worked
       ;; example -- referenced under :value/* in real content but, like
       ;; :vec3/launch and :vec3/scatter-end before it, never actually
       ;; implemented anywhere in the repo. Genuinely pure string parsing
       ;; (no RNG, no host state), so both accessors are ordinary :pure ops.
       :value/status-id (keyword (first (str/split (nth args 0) #":")))
       :value/status-max-amplifier (Long/parseLong (second (str/split (nth args 0) #":")))

       ;; mag_movement.edn's (S6) block/entity-type ids, comparing a
       ;; raycast hit's raw :block-id/:entity-type against a magnetic-
       ;; material allowlist. mcmod/runtime/expression-catalog.clj's own
       ;; worked example ("block.minecraft.iron_block") is a Minecraft
       ;; translation key, not the "minecraft:iron_block" namespaced-id
       ;; shape an allowlist actually contains -- drop the leading
       ;; type-prefix segment (the part before the first '.') and turn
       ;; the next '.' into the real ':' separator. A string with no '.'
       ;; at all (already namespaced) passes through unchanged, so this
       ;; is safe regardless of which shape the host actually hands back.
       ;; Same never-wired-up-reference class already established this
       ;; session (:vec3/launch, :value/status-id).
       :value/normalize-id
       (let [s (nth args 0) dot1 (.indexOf ^String s ".")]
         (if (neg? dot1)
           s
           (let [tail (subs s (inc dot1)) dot2 (.indexOf ^String tail ".")]
             (if (neg? dot2) tail (str (subs tail 0 dot2) ":" (subs tail (inc dot2)))))))

       :collection/contains? (boolean (some #(= % (nth args 1)) (or (nth args 0) [])))
       :collection/concat (vec (concat (or (nth args 0) []) (or (nth args 1) [])))
       :collection/remove (vec (remove #(= % (nth args 1)) (or (nth args 0) [])))
       :collection/first (first (or (nth args 0) []))
       :collection/nonempty (boolean (seq (nth args 0)))

       ;; A generic runtime map-key read -- needed anywhere a composite's
       ;; own :inputs declares a :map (or :any) parameter and must reach a
       ;; nested field of it: a NESTED-PATH {:ref [:input k ...path]} on a
       ;; composite's own declared input resolves at composite EXPAND time
       ;; (compile time), against whatever the call site supplied for k --
       ;; but a real caller almost always supplies an unresolved reference
       ;; of its own ({:ref [:input :style]}, not a literal map), and
       ;; get-in-ing into that reference silently returns nil. Wrapping the
       ;; read in {:expr :map/get ...} defers it to SAMPLE time instead, by
       ;; which point the argument has already been resolved against the
       ;; real runtime :input (the same reasoning as :vec3/x|y|z, added for
       ;; the identical bug class one property earlier).
       :map/get (get (nth args 0) (nth args 1))

       :bool/and (and (boolean (nth args 0)) (boolean (nth args 1)))
       :bool/or (or (boolean (nth args 0)) (boolean (nth args 1)))
       :bool/not (not (boolean (nth args 0)))

       :vec3/dot
       (let [[ax ay az] (vec3-components (nth args 0)) [bx by bz] (vec3-components (nth args 1))]
         (+ (* (double ax) (double bx)) (* (double ay) (double by)) (* (double az) (double bz))))
       :vec3/distance
       (let [[ax ay az] (vec3-components (nth args 0)) [bx by bz] (vec3-components (nth args 1))]
         (Math/sqrt (+ (Math/pow (- (double ax) (double bx)) 2)
                       (Math/pow (- (double ay) (double by)) 2)
                       (Math/pow (- (double az) (double bz)) 2))))
       :vec3/add
       (let [[ax ay az] (vec3-components (nth args 0)) [bx by bz] (vec3-components (nth args 1))]
         {:vec3 [(+ (double ax) (double bx)) (+ (double ay) (double by)) (+ (double az) (double bz))]})
       :vec3/sub
       (let [[ax ay az] (vec3-components (nth args 0)) [bx by bz] (vec3-components (nth args 1))]
         {:vec3 [(- (double ax) (double bx)) (- (double ay) (double by)) (- (double az) (double bz))]})
       :vec3/scale
       (let [[ax ay az] (vec3-components (nth args 0)) s (double (nth args 1))]
         {:vec3 [(* (double ax) s) (* (double ay) s) (* (double az) s)]})
       :vec3/with-z
       (let [[x y _z] (vec3-components (nth args 0))]
         {:vec3 [(double x) (double y) (double (nth args 1))]})
       :vec3/length
       (let [[x y z] (vec3-components (nth args 0))]
         (Math/sqrt (+ (* (double x) (double x)) (* (double y) (double y)) (* (double z) (double z)))))
       :vec3/x (double (nth (vec3-components (nth args 0)) 0))
       :vec3/y (double (nth (vec3-components (nth args 0)) 1))
       :vec3/z (double (nth (vec3-components (nth args 0)) 2))
       :vec3/normalize
       (let [[x y z] (vec3-components (nth args 0))
             len (Math/sqrt (+ (* (double x) (double x)) (* (double y) (double y)) (* (double z) (double z))))]
         (if (zero? len)
           {:vec3 [0.0 0.0 0.0]}
           {:vec3 [(/ (double x) len) (/ (double y) len) (/ (double z) len)]}))
       :vec3/approach
       (let [[fx fy fz] (vec3-components (nth args 0)) [tx ty tz] (vec3-components (nth args 1))
             step (Math/abs (double (nth args 2)))]
         {:vec3 [(approach-component fx tx step) (approach-component fy ty step) (approach-component fz tz step)]})
       ;; A ballistic launch vector: `direction` pitched up/down by
       ;; `pitch-offset` radians (rotated about the horizontal axis
       ;; perpendicular to `direction`, keeping its yaw), then scaled to
       ;; `speed`. This opcode was REFERENCED by real content
       ;; (vec_accel.edn, S6) under the old system but never actually
       ;; implemented anywhere -- this docstring once called it out as
       ;; "domain-specific, supplied through the NodeEnvironment", but an
       ;; exhaustive search of both combat-core and ac turned up no
       ;; :extra-ops map that ever registered it, so it would have thrown
       ;; "unsupported expression opcode" the one time it was actually
       ;; invoked. Since it is genuinely pure (no RNG, no host state), it
       ;; belongs in the core vec3 family rather than behind a NodeEnvironment
       ;; extension point that nothing else has ever needed -- this is a
       ;; new, from-scratch implementation, not a port of a working one.
       :vec3/launch
       (let [[dx dy dz] (vec3-components (nth args 0))
             speed (double (nth args 1))
             pitch-offset (double (nth args 2))
             horiz (Math/sqrt (+ (* dx dx) (* dz dz)))]
         (if (zero? horiz)
           ;; Straight up/down: yaw is undefined, so pitch-offset only
           ;; ever changes magnitude along the existing (vertical) axis.
           {:vec3 [0.0 (* speed (Math/signum (double dy))) 0.0]}
           (let [pitch (Math/atan2 dy horiz)
                 pitch' (+ pitch pitch-offset)
                 yaw-x (/ dx horiz) yaw-z (/ dz horiz)
                 cos-p (Math/cos pitch')]
             {:vec3 [(* speed cos-p yaw-x) (* speed (Math/sin pitch')) (* speed cos-p yaw-z)]})))

       :random/uniform (uniform seed (double (nth args 0)) (double (nth args 1)))
       :random/int (bounded-int seed (long (nth args 0)) (long (nth args 1)))
       :random/chance (< (unit-double seed) (double (nth args 0)))

       (if-let [f (get extra-ops opcode)]
         (f args seed)
         (throw (ex-info "unsupported expression opcode" {:opcode opcode})))))))
