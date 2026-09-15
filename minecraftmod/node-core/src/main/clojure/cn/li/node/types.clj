(ns cn.li.node.types
  "The type lattice for node-core :inputs/:outputs. Deliberately shallow --
   scalars, vec3/color literals, opaque handles, and two structural forms
   (:node for callback sub-trees, [:list-of t] for typed lists). Enough to
   drive editor validation and compile-time checks without becoming a
   general type system. See NODE_LANGUAGE.md section 3.")

(def scalar-types #{:double :long :boolean :keyword :string})
(def literal-types #{:vec3 :color})

;; Opaque handles: pass between primitives, render as an "object" pin in an
;; editor, never decomposed into scalars by node-core itself. New domain
;; handles (combat/vfx) are added here as the vocabulary grows -- adding one
;; is not a layering violation, it is still just a type tag.
(def opaque-types
  #{:hit-result :destination :block-placement :entity-ref :entity-list
    :entity-snapshot :block-list :owner-snapshot :item-snapshot
    :terrain-plan :beam-result :energy-target :render-op :map
    ;; :break-result is :block/break's {:status :block-id :position} report.
    ;; NOT a :block-placement -- that describes where a block would GO.
    ;;
    ;; No :hit-list tag here on purpose: list-ness already has a spelling,
    ;; the structural [:list-of t] that :target/entities and :target/blocks
    ;; return. (:entity-list and :block-list above are leftovers of a third
    ;; spelling and are referenced by nothing -- see the collection-type
    ;; phase.)
    :break-result
    ;; A materialized resource pool: resource-key -> amount. The one handle
    ;; here whose fields are NUMBERS rather than further handles, which is
    ;; why it is worth a tag at all -- while it was :any, every read off it
    ;; reached a numeric parameter as an unchecked :convert, and those were
    ;; the single largest group of such reads in shipped content.
    :resource-pool
    ;; :any is the one deliberate escape hatch, for genuinely generic
    ;; plumbing like :data/bind's :value field, which by design forwards
    ;; whatever type the caller's expression produces.
    :any})

(defn list-of? [t] (and (vector? t) (= :list-of (first t)) (= 2 (count t))))
(defn node-type? [t] (= :node t))

(defn known-type? [t]
  (boolean
   (or (contains? scalar-types t) (contains? literal-types t)
       (contains? opaque-types t) (node-type? t) (list-of? t))))

(defn- vec3-literal? [v]
  (and (map? v) (vector? (:vec3 v)) (= 3 (count (:vec3 v))) (every? number? (:vec3 v))))

(defn conforms?
  "Best-effort static type check for a LITERAL value against declared type
   `t`. Only meaningful when `value` is not itself a deferred expression
   ({:ref ...}/{:expr ...}) -- callers should skip this check for those,
   since their real type is only known once evaluated. Opaque handles and
   :node values are accepted structurally; verifying their internal shape
   is the producing/consuming primitive's own job, not the type system's."
  [t value]
  (cond
    (= t :double) (number? value)
    (= t :long) (integer? value)
    (= t :boolean) (boolean? value)
    (= t :keyword) (keyword? value)
    (= t :string) (string? value)
    (= t :vec3) (vec3-literal? value)
    (= t :color) (or (vector? value) (map? value))
    (list-of? t) (or (nil? value) (sequential? value))
    (node-type? t) (map? value)
    :else true))

(defn deferred?
  "True when `value` is an expression form resolved only at runtime/compile
   substitution time ({:ref ...} or {:expr ...}), for which conforms? cannot
   meaningfully judge a literal shape."
  [value]
  (and (map? value) (or (vector? (:ref value)) (keyword? (:expr value)))))

(defn graph-fragment?
  "True when `value` is a nested V4 graph fragment (inline :ref / :component /
   typed node), not a fully concrete EDN literal."
  [value]
  (or (deferred? value)
      (and (map? value) (keyword? (:component value)))
      (and (map? value) (keyword? (:type value)) (contains? value :nid))))

(defn literal-edn?
  "True when `value` is fully concrete EDN (numbers, keywords, nested maps of
   the same) with no graph fragments. Used to decide whether compile-time
   payload shape checks apply."
  [value]
  (cond
    (nil? value) true
    (number? value) true
    (boolean? value) true
    (string? value) true
    (keyword? value) true
    (graph-fragment? value) false
    (map? value) (every? literal-edn? (vals value))
    (vector? value) (every? literal-edn? value)
    :else false))

(def ^:private type-aliases
  "cn.li.node.schema (deleted; it had zero real callers anywhere in the repo
   -- only its own now-deleted test) authored a second, incompatible scalar
   vocabulary (:bool/:int/:float/...) alongside this namespace's real one
   (:boolean/:long/:double/...); AC's final_vocabulary.clj forked schema's
   names, not this lattice's. This table is NOT wired into known-type?/
   conforms? -- conforms? already accepts an unrecognized type via its
   :else true fallback, so nothing here needs it to keep working. It exists
   so a future migration of an :any-typed field to a real type can pick the
   ONE canonical name instead of reintroducing the old vocabulary."
  {:float :double, :int :long, :bool :boolean, :tick :long, :duration :long
   :angle :double, :ratio :double, :seed :long, :resource-id :keyword})

(defn canonical-type
  "The one lattice name for `t`, translating cn.li.node.schema's retired
   vocabulary if `t` was spelled that way. Idempotent: canonical-type on an
   already-canonical type is a no-op."
  [t]
  (get type-aliases t t))

(defn payload-conforms?
  "conforms? with the one leniency a VFX payload needs: a :vec3 input may be
   written as a bare [x y z] vector, not only the canonical {:vec3 [...]}
   literal. That difference is exactly why plain :type checking used to be
   skipped for payloads entirely -- shipped content writes both spellings,
   so the strict form alone would reject working effects."
  [want value]
  (if (= :vec3 want)
    (or (vec3-literal? value)
        (and (vector? value) (= 3 (count value)) (every? number? value)))
    (conforms? want value)))

(defn payload-problems
  "effect-id, its declared `:inputs` specs, and a literal spawn/update
   payload -> a vector of {:code :key :message ...} problems, empty when the
   payload agrees with the declaration. When `:require-inputs?` is true,
   inputs without a declared default must also be present and non-nil. This
   is used for literal `:spawn` payloads: omitting a primitive input otherwise
   leaves a nil in the VFX execution frame and fails only when the scene is
   sampled. Pure: callers decide whether to throw or collect (cn.li.node.compile
   reports these as ordinary diagnostics so the editor can list them and the
   catalog can refuse to start on them).

   Only LITERAL values are judged -- a payload slot wired to a graph node or
   `{:ref ...}` fragment has no statically known value, so it is skipped
   rather than guessed at."
  ([effect-id input-specs payload]
   (payload-problems effect-id input-specs payload {}))
  ([effect-id input-specs payload {:keys [require-inputs?]
                                   :or {require-inputs? false}}]
   (when (and (map? input-specs) (map? payload))
    (let [missing
          (when require-inputs?
            (for [[k spec] input-specs
                  :when (and (map? spec)
                             (not (:auto-provided? spec))
                             (or (:required? spec)
                                 (and (not (contains? spec :default))
                                      (not= :any (canonical-type (:type spec)))))
                             (not (contains? payload k)))]
              {:code :missing-vfx-input :severity :error :effect-id effect-id :key k
               :message (str "spawn payload for " effect-id
                             " is missing required input " k)}))
          nil-inputs
          (when require-inputs?
            (for [[k spec] input-specs
                  :when (and (map? spec)
                             (not (:auto-provided? spec))
                             (or (:required? spec)
                                 (and (not (contains? spec :default))
                                      (not= :any (canonical-type (:type spec)))))
                             (contains? #{:double :long :boolean}
                                        (canonical-type (:type spec)))
                             (contains? payload k)
                             (nil? (get payload k)))]
              {:code :nil-vfx-input :severity :error :effect-id effect-id :key k
               :message (str "spawn payload for " effect-id
                             " supplies nil for required input " k)}))
          unknown (for [k (keys payload)
                        :when (not (contains? input-specs k))]
                    ;; Was :warn, on the reasoning that an undeclared field is
                    ;; dead weight the effect ignores rather than a crash, and
                    ;; that shipped content had a few -- failing the catalog
                    ;; on them would have traded a silent bug for an unbootable
                    ;; game. That was true when written and is not now: the
                    ;; three real cases were removed in c90018a3c, so :error
                    ;; costs nothing today and is the only thing that stops
                    ;; them coming back. A warning nobody asserts on is how
                    ;; those three rode along in released content unnoticed in
                    ;; the first place.
                    {:code :unknown-vfx-field :severity :error :effect-id effect-id :key k
                     :message (str "effect " effect-id " declares no input " k
                                   " -- it would be silently ignored at runtime")})
          declared
          (for [[k spec] input-specs
                :when (and (map? spec) (contains? payload k))
                :let [v (get payload k)
                      mk (:map-keys spec)
                      want (canonical-type (:type spec))]
                :when (literal-edn? v)
                problem
                (cond
                  ;; :map-keys is the stricter contract; when declared it
                  ;; supersedes the (usually :any) :type tag.
                  mk (cond
                       (not (map? v))
                       [{:code :vfx-payload-shape :effect-id effect-id :key k
                         :message (str "input " k " of " effect-id " wants a map matching :map-keys "
                                       (vec (keys mk)) ", got " (pr-str v))}]
                       :else
                       (concat
                        (for [[fk _] mk :when (not (contains? v fk))]
                          {:code :vfx-payload-shape :effect-id effect-id :key k
                           :message (str "input " k " of " effect-id " is missing required key " fk)})
                        (for [[fk ft] mk
                              :let [fv (get v fk)
                                    fwant (canonical-type ft)]
                              :when (and (contains? v fk) (literal-edn? fv) (some? fv)
                                         (not (payload-conforms? fwant fv)))]
                          {:code :vfx-payload-shape :effect-id effect-id :key k
                           :message (str "input " k "." fk " of " effect-id " wants " fwant
                                         ", got " (pr-str fv))})))

                  (and (some? v) (not (payload-conforms? want v)))
                  [{:code :vfx-payload-type :effect-id effect-id :key k
                    :message (str "input " k " of " effect-id " wants " want
                                  ", got " (pr-str v))}]

                  :else nil)]
            problem)]
      (vec (concat missing nil-inputs unknown declared))))))

(defn assert-payload-literals!
  "Throwing wrapper over payload-problems, kept for callers that validate
   outside a compile env and have no diagnostic channel to report into."
  [effect-id input-specs payload]
  (when-let [[{:keys [message] :as problem}] (seq (payload-problems effect-id input-specs payload))]
    (throw (ex-info message (assoc problem :code :vfx-payload-shape))))
  nil)

;; --- register-bank plumbing for the surface-DSL compiler (cn.li.node.compile)
;; and the mcmod ExecutionFrame emitter it targets. Added alongside the
;; existing lattice above rather than replacing it: descriptor.clj/validate.clj
;; still consume known-type?/conforms?/canonical-type as-is until combat-core
;; and vfx-core are rewritten off the old engine (see verifyNodeCoreDependencyDirection
;; migration plan) -- these are pure additions.

(defn integral?
  "True when a value of type `t` belongs in a fixed-width integer register
   (the :longs/:booleans ExecutionFrame banks) rather than :doubles or
   boxed :objects."
  [t]
  (contains? #{:long :boolean} (canonical-type t)))

(defn numeric? [t] (contains? #{:double :long} (canonical-type t)))

(defn width
  "Register width for `t`: how many scalar slots one value of this type
   occupies in a structure-of-arrays layout (vfx-core particle columns,
   cn.li.vfx.layout). Everything that isn't a literal vector type is a
   single slot."
  [t]
  (case (canonical-type t) :vec3 3 :color 4 1))

(defn bank
  "Which ExecutionFrame register group a value of type `t` is stored in.
   :vec3/:color and every opaque handle live in :objects -- only genuine
   scalars get a primitive-array bank, so :objects is where boxing happens
   for compound values (see cn.li.mcmod.runtime.effect.emit)."
  [t]
  (let [t (canonical-type t)]
    (cond
      (= t :double) :doubles
      (= t :long) :longs
      (= t :boolean) :booleans
      :else :objects)))

(defn condition-type?
  "May a value of static type `t` be used as a when/if condition?

   :boolean obviously. Beyond that the test is CAN THIS REGISTER HOLD NIL,
   which `bank` already answers: a :doubles/:longs slot is a primitive JVM
   array element and cannot, so testing one is a condition that is always
   true -- always an authoring bug. An :objects slot can, and the lattice
   has no way to write `or nil`, so truthiness is how the language asks
   \"did I get one?\". `(let loc (target/saved-location ...)) (if loc ...)`
   is idiomatic and not a type confusion: that node returns nil when the
   name was never saved.

   [:list-of t] is the one :objects-bank exception. An EMPTY vector is
   truthy in Clojure, so `(if some-list ...)` reads as \"if non-empty\" and
   does not mean it. Use :collection/nonempty.

   Separate from assignable? on purpose: this is not \"is a handle a
   boolean\" (it is not, and nothing else should treat it as one), it is
   the one position where truthiness is the language's defined semantics.
   See NODE_LANGUAGE.md section 1's statement table."
  [t]
  (let [t (canonical-type t)]
    (or (= t :boolean)
        (and (= :objects (bank t)) (not (list-of? t))))))

(defn assignable?
  "Can a value of static type `from` be passed where `to` is declared?
   :any is a two-way gradual-typing escape hatch: it accepts everything
   (declaring a param :any) AND is accepted everywhere (a value whose
   static type is :any, e.g. a field-access result -- cn.li.node.compile
   has no per-field type schema, so (:position hit) is only ever known to
   be :any at compile time; treating :any as assignable to a concrete type
   is what lets that value flow into a :vec3-typed param, with the real
   shape check deferred to the host/emitter at the actual use site). :long
   widens to :double (the reverse does not hold -- a graph author writing a
   fractional literal where an :long parameter is declared is a real
   authoring bug, not implicit narrowing); every other pair requires an
   exact match. Used by cn.li.node.compile for compile-time parameter
   type-checking (Psi-style: reported at the DSL source position, not
   discovered at runtime)."
  [from to]
  (let [from (canonical-type from) to (canonical-type to)]
    (or (= to :any)
        (= from :any)
        (= from to)
        (and (= from :long) (= to :double)))))
