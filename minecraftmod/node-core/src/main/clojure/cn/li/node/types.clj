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
  #{:hit-result :destination :entity-ref :entity-list :block-list
    :owner-snapshot :item-snapshot :terrain-plan :beam-result
    :energy-target :render-op :map
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
