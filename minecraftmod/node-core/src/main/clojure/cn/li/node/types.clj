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
