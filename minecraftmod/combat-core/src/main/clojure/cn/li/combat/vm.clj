(ns cn.li.combat.vm
  "Allocation-conscious interpreter for the private combat IR."
  (:require [cn.li.mcmod.runtime.expr :as expr]
            [cn.li.mcmod.runtime.seeded-rng :as rng]
            [cn.li.mcmod.runtime.effect-contract :as effect-contract]
            [cn.li.combat.beam :as beam]
            [clojure.string :as str])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram ExecutionFrame HostTable]
           [clojure.lang IFn]
           [java.util ArrayList]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(def ^:const operand-stride 4)

(defn- finish-result [^ExecutionFrame frame ^long outcome-index finish-session?]
  {:status :finished :outcome outcome-index :finish-session? finish-session?
   :actions (.-actions frame) :vfx (.-vfx frame) :events (.-events frame)})

(defn- reject-result [^ExecutionFrame frame ^long reason-index]
  {:status :rejected :reason reason-index
   :actions (.-actions frame) :vfx (.-vfx frame) :events (.-events frame)})

(defn- vec3-components [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (and (map? value)
         (every? #(number? (get value %)) [:x :y :z]))
    [(double (:x value)) (double (:y value)) (double (:z value))]
    (vector? value) value
    :else (throw (ex-info "expected vec3 expression value" {:value value}))))

;; Indirect through a local binding rather than calling the ^double-hinted
;; `expr`/`rng` Vars directly. `evaluate-expression`'s `case` branches return
;; a mix of double/boolean/map, so the whole function is Object-typed; from
;; inside that context the compiler has (in practice, for 3-arg fns) picked
;; the boxed-return primitive interface (e.g. IFn$DDDO) for some call sites
;; while the callee only implements the primitive-return one it was hinted
;; for (IFn$DDDD) -- two distinct JVM method signatures sharing the name
;; `invokePrim`, so the mismatch throws AbstractMethodError at call time
;; rather than failing to compile. Binding the Var's value to a local first
;; forces a plain boxed IFn/.invoke dispatch, which every implementation
;; satisfies.
(def ^:private ^clojure.lang.IFn expr-add expr/add)
(def ^:private ^clojure.lang.IFn expr-sub expr/sub)
(def ^:private ^clojure.lang.IFn expr-mul expr/mul)
(def ^:private ^clojure.lang.IFn expr-div expr/div)
(def ^:private ^clojure.lang.IFn expr-clamp expr/clamp)
(def ^:private ^clojure.lang.IFn expr-lerp expr/lerp)
(def ^:private ^clojure.lang.IFn rng-uniform rng/uniform)
(def ^:private ^clojure.lang.IFn rng-bounded-int rng/bounded-int)
(def ^:private ^clojure.lang.IFn rng-next-long rng/next-long)
(def ^:private ^clojure.lang.IFn rng-unit-double rng/unit-double)

(defn- collection-contains?
  "Allocation-free linear membership for bounded EDN filter vectors."
  [values target]
  (let [values (if (vector? values) values (vec (or values [])))
        length (count values)]
    (loop [index 0]
      (if (< index length)
        (if (= (nth values index) target)
          true
          (recur (inc index)))
        false))))

(defn- approach-component
  [^double from ^double to ^double step]
  (let [delta (- (double to) (double from))]
    (if (<= (Math/abs delta) step)
      (double to)
      (+ (double from)
         (if (neg? delta) (- step) step)))))

(defn evaluate-expression
  ([opcode args] (evaluate-expression opcode args 0))
  ([opcode args ^long rng-state]
  (case opcode
    :math/add (double (expr-add (double (nth args 0)) (double (nth args 1))))
    :math/sub (double (expr-sub (double (nth args 0)) (double (nth args 1))))
    :math/mul (double (expr-mul (double (nth args 0)) (double (nth args 1))))
    :math/div (double (expr-div (double (nth args 0)) (double (nth args 1))))
    :math/min (double (min (double (nth args 0)) (double (nth args 1))))
    :math/max (double (max (double (nth args 0)) (double (nth args 1))))
    :math/abs (double (Math/abs (double (nth args 0))))
    :math/floor (double (Math/floor (double (nth args 0))))
    :math/sqrt (double (Math/sqrt (double (nth args 0))))
    :math/sin (double (Math/sin (double (nth args 0))))
    :math/cos (double (Math/cos (double (nth args 0))))
    :math/clamp (double (expr-clamp (double (nth args 0)) (double (nth args 1)) (double (nth args 2))))
    :math/lerp (double (expr-lerp (double (nth args 0)) (double (nth args 1)) (double (nth args 2))))
    :math/lt (< (double (nth args 0)) (double (nth args 1)))
    :math/lte (<= (double (nth args 0)) (double (nth args 1)))
    :math/eq (= (double (nth args 0)) (double (nth args 1)))
    ;; Generic value equality for data predicates (entity types, ids, modes).
    ;; Numeric equality remains :math/eq so its primitive fast path is kept.
    :value/eq (= (nth args 0) (nth args 1))
    :collection/contains? (collection-contains? (nth args 0) (nth args 1))
    :collection/concat (vec (concat (or (nth args 0) []) (or (nth args 1) [])))
    :value/normalize-id
    (let [id (some-> (nth args 0) str/lower-case)]
      (cond
        (nil? id) nil
        (str/includes? id ":") id
        (re-matches #"(?:block|item|entity)\\.[^.]+\\..+" id)
        (let [[_ namespace path]
              (re-matches #"(?:block|item|entity)\\.([^.]+)\\.(.+)" id)]
          (str namespace ":" path))
        :else id))
     :value/parse-status-spec
     (let [[effect-name amp-text] (str/split (str (nth args 0)) #":" 2)
           effect-name (str/trim (or effect-name ""))
           amplifier (try
                       (Long/parseLong (str/trim (or amp-text "0")))
                       (catch NumberFormatException _ 0))]
       {:status-id (keyword effect-name)
        :max-amplifier (if (< (long amplifier) 0) 0 amplifier)})
     :value/status-id
     (keyword (str/trim (or (first (str/split (str (nth args 0)) #":" 2)) "")))
     :value/status-max-amplifier
     (let [[_ amp-text] (str/split (str (nth args 0)) #":" 2)
           amplifier (try
                       (Long/parseLong (str/trim (or amp-text "0")))
                       (catch NumberFormatException _ 0))]
       (if (< (long amplifier) 0) 0 amplifier))
    :math/gte (>= (double (nth args 0)) (double (nth args 1)))
    :math/gt (> (double (nth args 0)) (double (nth args 1)))
    :math/select (if (boolean (nth args 0)) (nth args 1) (nth args 2))
    :collection/first (first (or (nth args 0) []))
    :collection/nonempty (boolean (seq (nth args 0)))
    :bool/and (and (boolean (nth args 0)) (boolean (nth args 1)))
    :bool/or (or (boolean (nth args 0)) (boolean (nth args 1)))
    :bool/not (not (boolean (nth args 0)))
    :vec3/dot (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                (expr/vec3-dot ax ay az bx by bz))
    :vec3/distance (let [[ax ay az] (vec3-components (nth args 0))
                         [bx by bz] (vec3-components (nth args 1))]
                     (expr/vec3-distance ax ay az bx by bz))
    :vec3/add (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                {:vec3 [(+ (double ax) (double bx))
                        (+ (double ay) (double by))
                        (+ (double az) (double bz))]})
    :vec3/sub (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                {:vec3 [(- (double ax) (double bx))
                        (- (double ay) (double by))
                        (- (double az) (double bz))]})
    :vec3/scale (let [[ax ay az] (vec3-components (nth args 0))
                      scale (double (nth args 1))]
                  {:vec3 [(* (double ax) scale)
                          (* (double ay) scale)
                          (* (double az) scale)]})
    :vec3/length (let [[x y z] (vec3-components (nth args 0))]
                   (Math/sqrt (+ (* (double x) (double x))
                                 (* (double y) (double y))
                                 (* (double z) (double z)))))
    :vec3/normalize (let [[x y z] (vec3-components (nth args 0))
                          length (Math/sqrt (+ (* (double x) (double x))
                                               (* (double y) (double y))
                                               (* (double z) (double z))))]
                      (if (zero? length)
                        {:vec3 [0.0 0.0 0.0]}
                        {:vec3 [(/ (double x) length)
                                (/ (double y) length)
                                (/ (double z) length)]}))
    :vec3/approach (let [[fx fy fz] (vec3-components (nth args 0))
                         [tx ty tz] (vec3-components (nth args 1))
                         step (Math/abs (double (nth args 2)))]
                     {:vec3 [(approach-component fx tx step)
                             (approach-component fy ty step)
                             (approach-component fz tz step)]})
    ;; Generic deterministic scatter geometry.  The endpoint is the sum of
    ;; an exact forward range vector and a second vector rotated by an
    ;; independently sampled pitch/yaw, matching the neutral projectile
    ;; semantics used by any ability that needs bounded radial spread.
    :vec3/scatter-end
    (let [[ox oy oz] (vec3-components (nth args 0))
          [lx ly lz] (vec3-components (nth args 1))
          range (double (nth args 2))
          angle (Math/toRadians (double (nth args 3)))
          half (* 0.5 angle)
          pitch (double (rng-uniform rng-state (- half) half))
          yaw (double (rng-uniform (rng-next-long rng-state) (- half) half))
          cp (Math/cos pitch) sp (Math/sin pitch)
          px (double lx)
          py (+ (* (double ly) cp) (* (double lz) sp))
          pz (- (* (double lz) cp) (* (double ly) sp))
          cy (Math/cos yaw) sy (Math/sin yaw)
          rx (+ (* px cy) (* pz sy))
          rz (- (* pz cy) (* px sy))]
      {:vec3 [(+ (double ox) (* range (double lx)) (* range rx))
              (+ (double oy) (* range (double ly)) (* range py))
              (+ (double oz) (* range (double lz)) (* range rz))]})
    :random/uniform (double (rng-uniform rng-state (double (nth args 0)) (double (nth args 1))))
    :random/int (long (rng-bounded-int rng-state (long (nth args 0)) (long (nth args 1))))
    :random/chance (< (double (rng-unit-double rng-state)) (double (nth args 0)))
    (throw (ex-info "unsupported expression opcode" {:opcode opcode})))))

(declare eval-node-value execute-component!)

(def ^:private query-capability-by-component
  {:target/raycast :raycast
   :target/resolve-destination :raycast
   :target/directional-destination-query :raycast
   :target/entities :entity/select
   :target/entity-snapshot :entity/snapshot
   :target/blocks :block/select
   :owner/snapshot :owner/snapshot
   :target/item-held :item/held
   :energy/target :energy/target
   ;; Beam traversal is a host primitive.  Its neutral request shape is still
   ;; handled by the existing raycast capability; the EDN composite owns the
   ;; reusable beam pipeline and supplies all policy values.
   :host/beam-trace :raycast})

(def ^:private action-capability-by-component
  {:inventory/consume :inventory/consume
   :combat/damage :entity/damage
   :entity/trigger-behavior :entity/trigger-behavior
   :entity/mark :entity/mark
   :energy/charge :energy/charge
   :combat/impulse :entity/impulse
   :entity/radial-impulse :entity/radial-impulse
   :motion/flight :motion/flight
   :motion/velocity :motion/velocity
   :owner/can-fly :owner/can-fly
   :combat/status :entity/status
   :entity/teleport :entity/teleport
   :entity/reset-fall-damage :entity/reset-fall-damage
   :entity/spawn :entity/spawn
   :entity/discard :entity/discard
   :entity/configure :entity/configure
   :motion/entity-velocity :motion/entity-velocity
   :projectile/schedule-beam :projectile/schedule-beam
   :block/break-budget :block/break-budget
   :block/break :block/break
   :block/random-break :block/random-break
   :world/sound :world/sound
   :world/lightning :world/lightning
   :world/explosion :world/explosion
   :projectile/redirect :projectile/redirect
   :resource/enforce-floor :resource/enforce-floor
   :resource/add :resource/add})

(defn- append-object! [^ArrayList output value]
  (.add output value)
  nil)

(defn- emit-component!
  ([^ExecutionFrame frame component data]
   (emit-component! frame component data nil))
  ([^ExecutionFrame frame component data context]
  (case component
    :effect/vfx (append-object! (.-vfx frame)
                                (effect-contract/vfx-signal
                                  {:effect-id (:effect-id data)
                                   :operation (or (:operation data) :spawn)
                                   :payload (:payload data)
                                   :instance-key (:instance-key data)
                                   :audience (:audience data)}))
    :domain/event (append-object! (.-events frame)
                                  (merge {:type (:event-type data)
                                          :event-type (:event-type data)}
                                         (:payload data)
                                         {:payload (:payload data)}))
    :session/patch (append-object! (.-actions frame)
                                   {:type :session-patch
                                    :entries (:entries data)})
    :owner/patch (append-object! (.-actions frame)
                                 {:type :owner-patch
                                  :entries (:entries data)})
    (when-let [capability (get action-capability-by-component component)]
      (append-object! (.-actions frame)
                      (effect-contract/action-request
                        (assoc (dissoc data :component)
                               :capability capability
                               :world-id (str (or (:world-id data) "unknown"))
                               :activation-seed (:activation-seed context)
                               ;; Neutral provenance metadata lets a host apply
                               ;; its generic damage pipeline without making
                               ;; Combat Core know any skill id.
                               :ability-id (:ability-id context))))))))

(defn- resolve-data [value context]
  (cond
    (and (map? value) (:expr value) (contains? value :args))
    (eval-node-value value context)
    (and (map? value) (:ref value))
    (eval-node-value value context)
    (and (map? value) (contains? value :from))
    (eval-node-value value context)
    (and (map? value) (contains? value :tunable))
    (eval-node-value value context)
    (and (map? value) (contains? value :invariant))
    (eval-node-value value context)
    (map? value)
    (reduce-kv (fn [result key nested]
                 (assoc result key (resolve-data nested context)))
               (empty value) value)
    (vector? value) (mapv #(resolve-data % context) value)
    :else value))

(defn- invoke-query-component!
  [^ExecutionFrame frame ^HostTable host component data context]
  (let [capability (get query-capability-by-component component)
        order (:query-order context)
        capability-index (when (and capability order)
                           (.indexOf ^java.util.List order capability))
        ^objects handlers (when host (.-queryHandlers host))
        ^IFn handler (when (and handlers (<= 0 (long (or capability-index -1)))
                                (< (long capability-index) (alength handlers)))
                       (aget handlers capability-index))
        request (effect-contract/query-request
                  (resolve-data
                    (merge {:capability capability
                            :owner (:owner context)
                            :world-id (str (or (get-in context [:context :world-id]) "unknown"))}
                           (when (= component :host/beam-trace)
                             {:query-kind :beam})
                           (when (= component :target/directional-destination-query)
                             {:query-kind :directional-destination})
                           (when (= component :target/resolve-destination)
                             {:query-kind :resolve-destination})
                           (when (= :penetration (get-in data [:policy :type]))
                             {:query-kind :penetration})
                           (dissoc data :result :component))
                    context))
        result (if (= component :host/beam-trace)
                 (beam/trace! host order frame request)
                 (when handler (.invoke handler request frame)))]
    (when-let [results* (:results* context)]
      (vswap! results* assoc (:result data) result))
    (when-let [slots* (:slots* context)]
      (vswap! slots* assoc (:result data) result))
    nil))

(defn- eval-node-value [value context]
  (cond
    (and (map? value) (:expr value))
    ;; Every :random/* opcode reduces to a pure fn of this rng-state, so
    ;; reusing the raw activation-seed for the whole activation (as before)
    ;; made every random/chance or random/uniform call in one activation
    ;; return the same result. Mixing in a call-scoped counter -- via the
    ;; same SplitMix64 step the RNG itself uses -- makes each call site draw
    ;; its own value while staying fully deterministic given the seed.
    (let [counter* (:rng-counter* context)
          call-index (if counter* (long (vswap! counter* inc)) 0)
          base-seed (long (or (:activation-seed context) 0))]
      (evaluate-expression (:expr value)
                           (mapv #(eval-node-value % context) (:args value))
                           (long (rng-next-long (unchecked-add base-seed call-index)))))
    (and (map? value) (:ref value))
    (let [[scope key & path] (:ref value)]
      (get-in (case scope
                :context (:context context)
                :param (:params context)
                :params (:params context)
                :session (:session-state context)
                :slot (or (when-let [slots* (:slots* context)] @slots*)
                          (:slots context))
                :input (:input context)
                ;; Design E: unknown scope fails the activation instead of
                ;; silently resolving to nil. No production content uses a
                ;; scope outside this set, so this cannot regress today's
                ;; abilities; it exists so a typo in future content is a
                ;; loud compile/runtime error, not a silently-inert node.
                (throw (ex-info "unknown :ref scope" {:scope scope :ref value})))
              (into [key] path)))
    ;; Design C (caster facade): the ability names a capability the caller
    ;; promises to provide, never the caller's own data shape -- see
    ;; combat_runtime.clj's caster-facade table, the only place that shape
    ;; is allowed to leak into combat-core.
    (and (map? value) (contains? value :from))
    (let [facade (:from context)]
      (if (contains? facade (:from value))
        (get facade (:from value))
        (throw (ex-info "caster facade does not provide this capability"
                        {:from (:from value)}))))
    ;; Design B: a tunable is a value already resolved (curve applied) once
    ;; at activation time -- the ability never sees the raw parameter pair
    ;; or the growth-curve math. `:path` is only meaningful for a :pair
    ;; tunable (design A/skill_config's :pair curve), whose (lo,hi) is
    ;; handed through uncurved for the ability to lerp against something
    ;; other than skill-exp.
    (and (map? value) (contains? value :tunable))
    (let [tunables (:tunables context)]
      (if (contains? tunables (:tunable value))
        (let [resolved (get tunables (:tunable value))]
          (if (seq (:path value))
            (get-in resolved (:path value))
            resolved))
        (throw (ex-info "tunable was not declared/materialized"
                        {:tunable (:tunable value)}))))
    ;; Design A: an invariant is declared once in the ability's own
    ;; :invariants block (e.g. an overload floor); nodes that enforce it
    ;; (:resource/enforce-floor) reference it by name instead of repeating
    ;; the same lerp expression at every call site.
    (and (map? value) (contains? value :invariant))
    (let [invariants (:invariants context)]
      (if (contains? invariants (:invariant value))
        (resolve-data (get invariants (:invariant value)) context)
        (throw (ex-info "invariant was not declared"
                        {:invariant (:invariant value)}))))
    :else value))

(defn- execute-component!
  [^ExecutionFrame frame ^HostTable host component data context]
  (case component
    :target/raycast (invoke-query-component! frame host component data context)
    :target/resolve-destination (invoke-query-component! frame host component data context)
    :target/directional-destination-query (invoke-query-component! frame host component data context)
    :target/entities (invoke-query-component! frame host component data context)
    :owner/snapshot (invoke-query-component! frame host component data context)
    :target/item-held (invoke-query-component! frame host component data context)
    :energy/target (invoke-query-component! frame host component data context)
    :target/blocks (invoke-query-component! frame host component data context)
    :host/beam-trace (invoke-query-component! frame host component data context)
    :flow/phases
    (let [phase (or (:phase context) :start)
          child (if (= phase :events)
                  (get-in data [:events (:event context)])
                  (get data phase))]
      (when child
        (execute-component! frame host (:component child)
                            (dissoc child :component) context)))
    :flow/sequence
    (loop [steps (seq (:steps data))]
      (when-let [child (first steps)]
        (let [result (execute-component! frame host (:component child)
                                         (dissoc child :component) context)]
          (if result
            result
            (recur (next steps))))))
    :flow/branch
    (let [condition (boolean (eval-node-value (:when data) context))
          child (if condition (:then data) (:else data))]
      (when child
        (execute-component! frame host (:component child)
                            (dissoc child :component) context)))
    :flow/foreach
    (let [items (let [value (or (resolve-data (:items data) context) [])]
                  (if (vector? value) value (vec value)))
          limit (min (count items) (max 0 (long (:limit data))))]
      (loop [index 0]
        (when (< index limit)
          (let [item (nth items index)]
          (when-let [slots* (:slots* context)]
            (vswap! slots* assoc (:as data) item)
            (when-let [index-as (:index-as data)]
              (vswap! slots* assoc index-as (long index))))
          (let [result (execute-component! frame host
                                           (:component (:body data))
                                           (dissoc (:body data) :component)
                                           context)
                control (when (map? result) (:control result))]
            (cond
              ;; Skip this item only -- the loop and everything after it
              ;; still run. This is what lets a per-item guard failure (e.g.
              ;; "this one entry can't afford its share of a shared budget")
              ;; drop just that entry instead of aborting the whole program,
              ;; which a bare truthy/nil return convention can't express.
              (= :skip-item control) (recur (inc index))
              ;; Stop iterating, but this is not itself a program finish --
              ;; whatever follows the foreach step in its enclosing sequence
              ;; still runs.
              (= :break-loop control) nil
              result result
              :else (recur (inc index))))))))
    :data/random-item
    (let [items (let [value (or (resolve-data (:items data) context) [])]
                  (if (vector? value) value (vec value)))
          counter* (:rng-counter* context)
          call-index (if counter* (long (vswap! counter* inc)) 0)
          seed (long (rng-next-long
                       (unchecked-add (long (or (:activation-seed context) 0))
                                      call-index)))
          selected (when (seq items)
                     (nth items
                          (int (rng/bounded-int
                                seed 0 (dec (count items))))))]
      (when-let [slots* (:slots* context)]
        (vswap! slots* assoc (:result data) selected))
      nil)
    :flow/control {:control (:signal data)}
    :flow/window
    (let [value (double (resolve-data (:value data) context))
          pass? (and (> value (double (:min-exclusive data)))
                     (<= value (double (:max-inclusive data))))
          child (if pass? (:on-pass data) (:on-fail data))]
      (execute-component! frame host (:component child)
                          (dissoc child :component) context))
    ;; Every compiled ability lowers to a single opcode-26 :component node
    ;; (see recipe.clj identity-lower), so this is the ONLY :flow/finish path
    ;; any real ability program takes -- the bytecode-level `finish-result`
    ;; helper above, which does carry :actions/:vfx/:events, is dead code no
    ;; current lowering ever emits. Omitting those keys here silently
    ;; discarded every action/vfx-signal/event any ability ever produced.
    :flow/finish {:status :finished :outcome (:outcome data)
                  :finish-session? (boolean (:finish-session? data))
                  :actions (.-actions frame) :vfx (.-vfx frame) :events (.-events frame)}
    :flow/once (let [key (resolve-data (:key data) context)
                     latches* (:latches* context)
                     seen? (and latches* (contains? @latches* key))]
                 (if seen?
                   (when-let [child (:on-duplicate data)]
                     (execute-component! frame host (:component child)
                                         (dissoc child :component) context))
                   (do
                     (when latches* (vswap! latches* conj key))
                     (when-let [child (:on-first data)]
                       (execute-component! frame host (:component child)
                                           (dissoc child :component) context)))))
    :data/bind
    (do
      (when-let [slots* (:slots* context)]
        (vswap! slots* assoc (:to data) (resolve-data (:value data) context)))
      nil)
    :guard/resource
    (let [resources (or (some-> (:resources* context) deref)
                        (get-in context [:context :resources]) {})
          cost (:cost data)]
      (every? (fn [[key value]]
                (>= (double (or (get resources key) 0.0))
                    (double (resolve-data value context))))
              cost))
    :guard/value-in
    (contains? (set (resolve-data (:one-of data) context))
               (resolve-data (:value data) context))
    ;; Schema v2 design A: a named budget declared once in the ability's own
    ;; :costs block, spent by reference here. This deliberately emits the
    ;; SAME neutral :owner/patch action shape the ability-authored :program
    ;; used to build by hand -- the AC-side commit path (edn-owner-patch-
    ;; commands) already correctly translates every resource key in one
    ;; action's :entries, so :cost/spend needed no new commit machinery,
    ;; only a declarative place for the cost to live once instead of
    ;; wherever it happened to be spent.
    :cost/spend
    (let [spec (get-in context [:costs (:budget data)])
          scale (when (contains? data :scale) (double (resolve-data (:scale data) context)))
          amounts (into {}
                        (map (fn [[key value]]
                               [key (* (double (resolve-data value context))
                                       (double (or scale 1.0)))]))
                        (:resources spec))
          resources* (:resources* context)
          available (or (some-> resources* deref)
                        (get-in context [:context :resources]) {})
          affordable? (every? (fn [[key amount]]
                                (>= (double (or (get available key) 0.0)) (double amount)))
                              amounts)]
      (let [partial? (true? (:partial? data))
            spend (if affordable?
                    amounts
                    (if partial?
                      (into {}
                            (map (fn [[key amount]]
                                   [key (min (double (or (get available key) 0.0))
                                             (double amount))])
                                 amounts))
                      {}))]
      (if (or affordable? partial?)
        (do
          (when resources*
            (vswap! resources* (fn [resources]
                                (reduce-kv (fn [result key amount]
                                             (update result key (fnil - 0.0) amount))
                                           resources spend))))
          (when (seq spend)
            (emit-component! frame :owner/patch
                             {:entries (mapv (fn [[key amount]]
                                               {:path [:resources key]
                                                :mode :increment :value (- (double amount))})
                                             spend)}))
          nil)
        (when-let [on-insufficient (:on-insufficient data)]
          (execute-component! frame host (:component on-insufficient)
                              (dissoc on-insufficient :component) context)))))
    ;; A tagged experience mark: the ability names WHAT happened (:tag), the
    ;; ability's own :progression block (not this node) says how much that
    ;; is worth, and the ability id -- which the program never names itself
    ;; -- comes from context, supplied by the caller, not authored here.
    :score/mark
    (let [spec (get-in context [:progression (:tag data)])
          weight (when (contains? data :weight) (double (resolve-data (:weight data) context)))
          per-mark (double (or (resolve-data (:per-mark spec) context) 0.0))
          amount (* per-mark (double (or weight 1.0)))
          ability-id (get-in context [:context :ability-id])]
      (emit-component! frame :owner/patch
                       {:entries [{:path [:ability-data :skill-exps ability-id]
                                   :mode :increment :value amount}]})
      nil)
    :cooldown/start
    (let [cooldown-name (:name data)
          spec (get-in context [:cooldown cooldown-name])
          ticks (long (or (resolve-data (:ticks spec) context) 0))
          ability-id (get-in context [:context :ability-id])]
      (emit-component! frame :owner/patch
                       {:entries [{:path [:cooldown-data ability-id cooldown-name]
                                   :mode :assign :value (double ticks)}]})
      nil)
    :txn/atomic
    (let [guards (every? (fn [guard]
                           (boolean
                             (execute-component! frame host
                                                  (:component guard)
                                                  (dissoc guard :component)
                                                  context)))
                         (:guards data))]
      (if guards
        (do
          (doseq [reservation (:reservations data)]
            (execute-component! frame host (:component reservation)
                                (dissoc reservation :component) context))
          (let [body-result (when-let [body (:body data)]
                              (execute-component! frame host (:component body)
                                                  (dissoc body :component) context))]
            ;; :on-success, when present, is what determines the transaction's
            ;; outcome (some content uses a bare action for :body and puts the
            ;; actual :flow/finish in :on-success). When absent, :body's own
            ;; result -- which may itself be a terminal :flow/finish -- IS the
            ;; transaction's result and must not be discarded: doing so used
            ;; to silently swallow every successful guarded activation's
            ;; finish, leaving the bytecode loop to fall off the single
            ;; compiled instruction and crash with an index-out-of-bounds.
            (if-let [on-success (:on-success data)]
              (execute-component! frame host (:component on-success)
                                  (dissoc on-success :component) context)
              body-result)))
        (when-let [on-fail (:on-fail data)]
          (execute-component! frame host (:component on-fail)
                              (dissoc on-fail :component) context))))
    (emit-component! frame component (resolve-data data context) context)))

(defn- query! [^CompiledProgram program ^ExecutionFrame frame ^HostTable host
              capability-id request-index result-slot]
  (let [^objects constants (.-objectConstants program)
        request (aget constants request-index)
        ^objects objects (.-objects frame)]
    (when host
      (let [^objects handlers (.-queryHandlers host)
            ^IFn handler (aget handlers capability-id)]
        (aset objects result-slot (.invoke handler request frame))))
    nil))

(defn execute!
  ([^CompiledProgram program ^ExecutionFrame frame ^HostTable host entry]
   (execute! program frame host entry {}))
  ([^CompiledProgram program ^ExecutionFrame frame ^HostTable host entry context]
   (let [^ints opcodes (.-opcodes program)
         ^ints operands (.-operands program)
         ^doubles constants (.-doubleConstants program)
         ^longs long-constants (.-longConstants program)
         ^objects object-constants (.-objectConstants program)
         ^doubles doubles (.-doubles frame)
         ^longs longs (.-longs frame)
         ^booleans booleans (.-booleans frame)
         ^objects objects (.-objects frame)]
     (loop [pc (long entry)]
       (let [base (* pc operand-stride)]
         (case (aget opcodes pc)
           1 (do (aset-double doubles (aget operands base)
                              (aget constants (aget operands (inc base))))
                 (recur (inc pc)))
           2 (do (aset-long longs (aget operands base)
                            (aget long-constants (aget operands (inc base))))
                 (recur (inc pc)))
           3 (do (aset-boolean booleans (aget operands base)
                               (not (zero? (aget operands (inc base)))))
                 (recur (inc pc)))
           4 (do (aset objects (aget operands base)
                         (aget object-constants (aget operands (inc base))))
                 (recur (inc pc)))
           5 (do (aset-double doubles (aget operands base)
                              (double (get-in context [:params (aget operands (inc base))] 0.0)))
                 (recur (inc pc)))
           6 (do (aset objects (aget operands base)
                         (get-in context [:context (aget operands (inc base))]))
                 (recur (inc pc)))
           7 (do (aset-double doubles (aget operands base)
                              (aget doubles (aget operands (inc base))))
                 (recur (inc pc)))
           8 (let [dst (aget operands base)
                   opcode (aget object-constants (aget operands (inc base)))
                   args [(aget doubles (aget operands (+ base 2)))
                         (aget doubles (aget operands (+ base 3)))]]
               (aset-double doubles dst (double (evaluate-expression opcode args)))
               (recur (inc pc)))
           9 (do (query! program frame host (aget operands base)
                         (aget operands (inc base)) (aget operands (+ base 2)))
                 (recur (inc pc)))
           10 (recur (aget operands base))
           11 (if (aget booleans (aget operands base))
                (recur (inc pc))
                (recur (aget operands (inc base))))
           12 (do (aset objects (aget operands (inc base))
                           {:batch (aget objects (aget operands base))
                            :index 0 :limit (aget operands (+ base 2))})
                   (recur (inc pc)))
           13 (let [iterator (aget objects (aget operands base))
                    index (long (get iterator :index 0))
                    limit (long (get iterator :limit 0))]
                (if (< index limit)
                  (do (aset objects (aget operands (inc base))
                           (nth (get iterator :batch) index nil))
                      (aset objects (aget operands base) (assoc iterator :index (inc index)))
                      (recur (inc pc)))
                  (recur (aget operands (+ base 2)))))
           14 (do (aset-boolean booleans (aget operands (+ base 2)) true)
                  (recur (inc pc)))
           15 (do (append-object! (.-actions frame)
                                  {:type :session-patch :index (aget operands base)})
                  (recur (inc pc)))
           16 (do (append-object! (.-actions frame)
                                  {:type :owner-patch :index (aget operands base)})
                  (recur (inc pc)))
            17 (do (aset objects (aget operands base) {}) (recur (inc pc)))
            18 (let [transaction (aget objects (aget operands base))
                     ^IFn handler (when host (.-preflightHandler host))
                     accepted (if handler (boolean (.invoke handler transaction)) true)]
                 (if accepted
                   (recur (inc pc))
                   (recur (aget operands (inc base)))))
            19 (do (append-object! (.-actions frame)
                                   {:type :txn-reservation
                                    :index (aget operands base)})
                   (recur (inc pc)))
            20 (let [transaction (aget objects (aget operands base))
                     ^IFn handler (when host (.-commitHandler host))
                     committed (if handler
                                 (.invoke handler transaction frame)
                                 true)]
                 (if committed
                   (do (aset objects (aget operands base) committed)
                       (recur (inc pc)))
                   (recur (aget operands (inc base)))))
           21 (do (append-object! (.-actions frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           22 (do (append-object! (.-vfx frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           23 (do (append-object! (.-events frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           24 (finish-result frame (aget operands base)
                             (not (zero? (aget operands (inc base)))))
           25 (reject-result frame (aget operands base))
           26 (let [node (aget object-constants (aget operands base))
                    component (:component node)]
                ;; A compiled ability is always exactly one opcode-26 (see
                ;; recipe.clj's identity-lower), so falling through to nil
                ;; here means the whole component tree ran to completion
                ;; without ever reaching a :flow/finish -- every real
                ;; program path must terminate in one. Silently recur-ing
                ;; past the program's only instruction used to read the
                ;; opcode array out of bounds instead of naming the actual
                ;; problem.
                (or (execute-component! frame host component
                                        (dissoc node :component) context)
                    (throw (ex-info "component tree finished without reaching :flow/finish"
                                    {:component component}))))
           (throw (ex-info "unknown combat opcode"
                           {:pc pc :opcode (aget opcodes pc)}))))))))

(defn invoke-query! [^HostTable host ^long capability-id request output]
  (let [^objects handlers (.-queryHandlers host)
        ^IFn handler (aget handlers capability-id)]
    (.invoke handler request output)))
