(ns cn.li.node.compile
  "Surface DSL -> IR compiler.

   Compiles directly to registers instead of textually substituting/renaming
   EDN (the old cn.li.node.composite approach): a :defn call is inlined by
   binding the callee's declared params to freshly-allocated registers and
   recursively compiling its body into the caller's block, so there is
   nothing to rename -- registers are unique by construction (a fresh
   counter), never by gensym-on-symbol-text.

   NOT literal SSA (see cn.li.node.ir's docstring): branch/loop merge points
   reuse one mutable register instead of introducing a phi. `each` bodies
   desugar entirely to :pure operations (:collection/count, :collection/nth,
   :long/inc) plus :copy/:branch/:jump -- iterating an already-produced list
   value is pure/deterministic, so it needs no host round-trip.

   Grammar (every statement and node/fn call is a list; field access and
   node/fn calls are told apart by head shape -- a keyword head is field
   access, a symbol head is always a call):

     (let sym expr-or-call)      bind a register, visible to later
                                  statements in the SAME list only
     (when cond-expr stmt*)      no else; cond-expr must be :boolean
     (each sym coll-expr stmt*)  coll-expr must be a list-typed or :any value
     (finish {:outcome kw ...})  terminates the current block
     (ns/node-id {..map..})      call a vocab node (query if :returns is
                                  declared, action otherwise); map keys are
                                  the node's declared params
     (fn-id expr...)             inline a :defn function; args are
                                  positional, evaluated in the CALLER's
                                  scope (ordinary value passing, not dynamic
                                  scoping -- ?/$ sigils inside a :defn body
                                  only ever resolve that function's own
                                  :params, never a caller's tunable/
                                  capability -- :defn params carry no sigil)
     (:field expr)               field access

   Expression position (pure-op/field-access/literal/symbol args, node/fn
   call arguments, when/each conditions) never contains a further node/fn
   call -- only cn.li.node.ops pure operators, field access, literals, and
   symbols. This is what makes a single-consumer :pure/:get/:tun/:cap
   register safe to inline when pretty-printing (cn.li.node.pretty): none
   of those forms can reorder a host side effect, unlike :query/:action
   results, which always print through a `let` regardless of consumer
   count.

   Every compiling function that can touch control flow returns
   {:reg register-or-nil :block-id current-block-id}: block-id can change
   even mid-expression, because inlining a :defn whose body contains a
   `when`/`each` splices new blocks into the CALLER, so callers always
   thread the returned :block-id forward rather than assuming it is
   unchanged."
  (:require [clojure.string :as str]
            [cn.li.node.ops :as ops]
            [cn.li.node.types :as types]
            [cn.li.node.ir :as ir]
            [cn.li.node.surface :as surface]))

(def ^:const max-inline-depth 32)

;; --- compile-time environment -------------------------------------------
;;
;; `env` bundles the mutable per-compile atoms (registers/blocks/constants/
;; nid counter/diagnostics) with the static tables (vocab/capabilities/
;; tunable-types/fns) and the active reporting mode. Locals and inline depth
;; are NOT part of env -- they are lexically scoped, threaded as plain
;; function arguments/return values instead (see compile-stmt).

(defn- new-env [{:keys [vocab capabilities tunable-types state-types fns mode]}]
  {:vocab (or vocab {})
   :capabilities (or capabilities {})
   :tunable-types (or tunable-types {})
   :state-types (or state-types {})
   :fns (or fns {})
   :mode mode
   :reg-counters (atom {:doubles 0 :longs 0 :booleans 0 :objects 0})
   :reg-types (atom {})
   :const-pools (atom {:doubles [] :longs [] :booleans [] :objects []})
   :block-registry (atom {})
   :next-block-id (atom 0)
   :nid-counter (atom 0)
   :diagnostics (atom [])})

(defn- nid! [env] (str "n" (swap! (:nid-counter env) inc)))

(defn- nid-for!
  "The nid for an AUTHOR-VISIBLE instruction (one that corresponds to a
   real node in the graph editor's view: a node/fn call, a $/?/% sigil
   read, a field access, a pure-op call, a when/if/each/finish/state!/
   set!/event!/vfx! statement -- see cn.li.node.compile's own namespace
   docstring for the statement/expression grammar these are drawn from).
   Prefers `form`'s own :nid metadata (stamped by cn.li.node.nid/stamp, or
   hand-written by an author as ^{:nid \"n7\"} (...)) so the SAME
   instruction keeps the SAME id across edits that insert/reorder other
   statements -- inserting a statement at the top of a :do block must not
   shift every later node's identity, which a bare counter (nid!) cannot
   avoid. Compiler-INTERNAL instructions (bank :convert, each's own
   index/count bookkeeping, the implicit :jump closing a when/if arm, the
   synthetic trailing :finish a fall-off-the-end block gets) have no
   author form and keep using nid! directly -- they have no stable
   identity to preserve because they are not something an editor would
   ever let a user select."
  [env form]
  (or (:nid (meta form)) (nid! env)))

(defn- pos-of [form]
  (let [m (meta form)]
    (when (:line m) {:line (:line m) :column (:column m)})))

(defn- report!
  "In :throw mode, throws immediately with the diagnostic as ex-data. In
   :collect mode, records the diagnostic and returns nil -- callers are
   responsible for producing their own recovery value (see
   dummy-register!) so compilation can keep going and surface every error
   in one pass, not just the first."
  [env {:keys [code message form nid want] :as diag}]
  (let [entry (merge {:severity :error :code code :message message :nid nid :want want}
                     (pos-of form))]
    (case (:mode env)
      :throw (throw (ex-info message entry))
      :collect (swap! (:diagnostics env) conj entry))))

;; --- register/constant/block allocation ----------------------------------

(defn- alloc-reg! [env bank type]
  (let [slot (get @(:reg-counters env) bank)]
    (swap! (:reg-counters env) update bank inc)
    (let [r [:reg bank slot]]
      (swap! (:reg-types env) assoc r type)
      r)))

(defn- alloc-const! [env bank type value]
  (let [existing (->> (get @(:const-pools env) bank)
                      (keep-indexed (fn [i v] (when (= v value) i)))
                      first)
        slot (or existing
                 (do (swap! (:const-pools env) update bank conj value)
                     (dec (count (get @(:const-pools env) bank)))))
        r [:const bank slot]]
    (swap! (:reg-types env) assoc r type)
    r))

(defn- dummy-literal [t]
  (case (types/canonical-type t)
    :double 0.0 :long 0 :boolean false :string "" :keyword :node/recovery
    :vec3 {:vec3 [0.0 0.0 0.0]}
    nil))

(defn- dummy-register!
  "Recovery value used only in :collect mode after a reported error, so
   downstream compilation of the same document can keep going instead of
   cascading unrelated failures from a missing register."
  [env want]
  (let [t (or want :any)]
    (alloc-const! env (types/bank t) t (dummy-literal t))))

(defn- new-block! [env]
  (let [id @(:next-block-id env)]
    (swap! (:next-block-id env) inc)
    (swap! (:block-registry env) assoc id (atom []))
    id))

(defn- append! [env block-id instr]
  (swap! (get @(:block-registry env) block-id) conj instr)
  instr)

(defn- type-of [env reg] (get @(:reg-types env) reg))

;; --- type coercion ---------------------------------------------------------

(defn- coerce!
  "Insert a :convert instruction when `reg` (of static type `from`) is used
   where `to` is declared, IFF the two types live in different
   ExecutionFrame register banks (see cn.li.node.types/bank) -- e.g. :long
   widening to :double is a real numeric conversion (:doubles and :longs
   are physically distinct primitive arrays), and a :double flowing into an
   :any-typed slot needs boxing. Same-bank conversions (e.g. :any -> :vec3,
   or :vec3 -> :any: both live in the :objects bank as boxed references)
   need no instruction at all -- the register is reused as-is; only the
   compile-time type recorded at its ORIGINAL allocation site changes
   meaning at this particular use, which is exactly what the :any
   gradual-typing escape hatch means (see types/assignable?). Returns `reg`
   unchanged whenever no bank change is needed."
  [env block-id reg from to]
  (let [from (types/canonical-type from) to (types/canonical-type to)]
    (if (or (= from to) (= (types/bank from) (types/bank to)))
      reg
      (let [dst (alloc-reg! env (types/bank to) to)]
        (append! env block-id {:op :convert :nid (nid! env) :dst dst :src reg :from from :to to})
        dst))))

;; --- literal compilation ---------------------------------------------------

(defn- vec3-literal? [form]
  (and (vector? form) (= 3 (count form)) (every? number? form)))

(defn- compile-literal! [env form]
  (cond
    (integer? form) (alloc-const! env :longs :long (long form))
    (float? form) (alloc-const! env :doubles :double (double form))
    (boolean? form) (alloc-const! env :booleans :boolean (boolean form))
    (string? form) (alloc-const! env :objects :string form)
    (keyword? form) (alloc-const! env :objects :keyword form)
    (vec3-literal? form) (alloc-const! env :objects :vec3 {:vec3 (mapv double form)})
    (nil? form) (alloc-const! env :objects :any nil)
    :else (throw (ex-info "not a literal form" {:form form}))))

(defn- literal? [form]
  (or (number? form) (boolean? form) (string? form) (keyword? form) (nil? form) (vec3-literal? form)))

;; --- forward declarations --------------------------------------------------

(declare compile-form compile-stmt compile-stmts!)

(defn- call-id [sym] (keyword (namespace sym) (name sym)))

;; --- node/fn call compilation ----------------------------------------------

(defn- compile-args-map!
  "Compile a node call's {:key expr ...} arg map against its declared
   :params, type-checking, defaulting, and coercing. Returns {key register}.
   Args are always compiled with allow-calls?=false: node/fn call arguments
   are pure expressions in this grammar, never a further call."
  [env locals block-id node-id spec arg-map form]
  (let [params (:params spec)]
    (doseq [k (remove #(contains? params %) (keys arg-map))]
      (report! env {:code :unknown-param :form form
                   :message (str "unknown param " k " for node " node-id)}))
    (doseq [[k pspec] params]
      (when (and (not (contains? arg-map k)) (not (contains? pspec :default)))
        (report! env {:code :missing-param :form form
                     :message (str "missing required param " k " for node " node-id)})))
    (into {}
          (keep (fn [[k v-form]]
                  (when (contains? params k)
                    (let [want (:type (get params k))
                          {:keys [reg]} (compile-form env locals block-id 0 v-form false)
                          got (type-of env reg)]
                      (when-not (types/assignable? got want)
                        (report! env {:code :type-mismatch :form v-form :want want
                                     :message (str "param " k " of " node-id " wants " want " got " got)}))
                      [k (coerce! env block-id reg got want)]))))
          arg-map)))

(defn- compile-node-call [env locals block-id node-id form]
  (let [spec (get (:vocab env) node-id)]
    (if-not spec
      {:reg (dummy-register! env :any) :block-id block-id}
      (let [arg-map (or (second form) {})
            args (compile-args-map! env locals block-id node-id spec arg-map form)
            returns (:returns spec)
            dst (when returns (alloc-reg! env (types/bank returns) returns))]
        (append! env block-id
                 (cond-> {:op (if returns :query :action) :nid (nid-for! env form) :node node-id :args args
                         ;; Baked in at compile time so the emitter
                         ;; (cn.li.mcmod.runtime.effect-emit) never needs
                         ;; its own node-id -> capability lookup -- it must
                         ;; stay domain-neutral (mcmod has zero project
                         ;; deps), so the ONE place that legitimately knows
                         ;; the vocab is here.
                         :capability (or (:capability spec) node-id)}
                   (:barrier? spec) (assoc :barrier? true)
                   dst (assoc :dst dst)))
        {:reg dst :block-id block-id}))))

(defn- compile-fn-call
  "Inlines fn-id's body at the call site. When the :defn declares :returns
   (a local name bound somewhere in its body -- see
   cn.li.node.surface/normalize's :defn case), that local's register
   becomes this call's result, letting a :defn be used as an expression
   (`let dest (ac/some-fn ...)`) exactly like a vocab query -- the
   original composites this is closing the gap for (target/raycast-
   destination and friends) are fundamentally value-producing, not the
   purely-effectful shape :defn only supported before this."
  [env locals block-id depth fn-id form]
  (when (>= depth max-inline-depth)
    (report! env {:code :inline-depth-exceeded :form form
                 :message (str "inlining " fn-id " exceeded max depth " max-inline-depth)}))
  (if-let [{:keys [params body returns]} (get (:fns env) fn-id)]
    (let [arg-forms (vec (rest form))]
      (when (not= (count arg-forms) (count params))
        (report! env {:code :arity-mismatch :form form
                     :message (str fn-id " expects " (count params) " args, got " (count arg-forms))}))
      (let [callee-locals
            (into {}
                  (map (fn [{:keys [name type]} arg-form]
                         (let [{:keys [reg]} (compile-form env locals block-id depth arg-form false)
                               got (type-of env reg)]
                           (when-not (types/assignable? got type)
                             (report! env {:code :type-mismatch :form arg-form :want type
                                          :message (str "param " name " of " fn-id " wants " type " got " got)}))
                           [name {:reg (coerce! env block-id reg got type)}]))
                       params arg-forms))
            {result-locals :locals final-block :block-id :as result}
            (compile-stmts! env callee-locals block-id (inc depth) body)]
        (if returns
          (if-let [{:keys [reg]} (get result-locals returns)]
            {:reg reg :block-id final-block}
            (do (report! env {:code :unknown-return-local :form form
                             :message (str fn-id " declares :returns " returns
                                         " but no such local is bound in its body")})
                {:reg (dummy-register! env :any) :block-id final-block}))
          result)))
    (do (report! env {:code :unknown-call-target :form form :message (str "no :defn named " fn-id)})
        {:reg nil :block-id block-id})))

(defn- compile-call [env locals block-id depth form allow-calls?]
  (let [head (first form)]
    (if-not allow-calls?
      (do (report! env {:code :call-not-allowed-here :form form
                       :message (str "call to " head " is not allowed in a pure expression")})
          {:reg (dummy-register! env :any) :block-id block-id})
      (let [id (call-id head)]
        (cond
          (contains? (:fns env) id) (compile-fn-call env locals block-id depth id form)
          (contains? (:vocab env) id) (compile-node-call env locals block-id id form)
          :else (do (report! env {:code :unknown-call-target :form form
                                 :message (str "no vocab node or :defn named " id)})
                    {:reg (dummy-register! env :any) :block-id block-id}))))))

;; --- expression compilation -------------------------------------------------

(defn- compile-symbol
  "Uses (str form), NOT (name form): name strips a symbol's namespace
   segment, and the leading $/? sigil only ever appears there for a
   namespaced reference like ?caster/eye -- see cn.li.node.surface/sigil's
   docstring for the same bug once found here."
  [env locals block-id form]
  (let [s (str form)]
    (cond
      (str/starts-with? s "$")
      (let [k (surface/str->keyword (subs s 1)) t (get (:tunable-types env) k)]
        (if (nil? t)
          {:reg (do (report! env {:code :unknown-tunable :form form
                                 :message (str "undeclared tunable $" (name k))})
                   (dummy-register! env :double))
           :block-id block-id}
          (let [dst (alloc-reg! env (types/bank t) t)]
            (append! env block-id {:op :tun :nid (nid-for! env form) :dst dst :key k})
            {:reg dst :block-id block-id})))

      (str/starts-with? s "?")
      ;; ((:capabilities env) k), NOT (get ...): :capabilities may be a
      ;; plain {key type} map (already callable as a fn, so this is a
      ;; no-op change for that case) OR a real function -- some capability
      ;; FAMILIES (?budget/fire, ?cooldown/main, ...) are named per-ability
      ;; by its own :costs/:cooldown declarations, not fixed repo-wide, so
      ;; a caller with dynamic families needs to pattern-match on the
      ;; namespace instead of enumerating every name in a static map.
      (let [k (surface/str->keyword (subs s 1)) t ((:capabilities env) k)]
        (if (nil? t)
          {:reg (do (report! env {:code :unknown-capability :form form
                                 :message (str "undeclared capability ?" (name k))})
                   (dummy-register! env :any))
           :block-id block-id}
          (let [dst (alloc-reg! env (types/bank t) t)]
            (append! env block-id {:op :cap :nid (nid-for! env form) :dst dst :key k})
            {:reg dst :block-id block-id})))

      ;; %mode reads session state key :mode (cn.li.node.surface's :state
      ;; sigil) -- a third read-only reference kind alongside $tunable/
      ;; ?capability, all three sharing the same "declared in :tunables/
      ;; :state, typed, read via one IR op" shape. Writing state is a
      ;; statement, not an expression -- see compile-stmt's `state!` case.
      (str/starts-with? s "%")
      (let [k (surface/str->keyword (subs s 1)) t (get (:state-types env) k)]
        (if (nil? t)
          {:reg (do (report! env {:code :unknown-state-key :form form
                                 :message (str "undeclared state key %" (name k))})
                   (dummy-register! env :any))
           :block-id block-id}
          (let [dst (alloc-reg! env (types/bank t) t)]
            (append! env block-id {:op :state-read :nid (nid-for! env form) :dst dst :key k})
            {:reg dst :block-id block-id})))

      :else
      (if-let [{:keys [reg]} (get locals form)]
        {:reg reg :block-id block-id}
        {:reg (do (report! env {:code :unbound-local :form form
                               :message (str "unbound local " form)})
                 (dummy-register! env :any))
         :block-id block-id}))))

(defn- compile-field-access [env locals block-id depth form k sub-form]
  (let [{:keys [reg block-id]} (compile-form env locals block-id depth sub-form false)
        dst (alloc-reg! env :objects :any)]
    (append! env block-id {:op :get :nid (nid-for! env form) :dst dst :src reg :key k})
    {:reg dst :block-id block-id}))

(defn- compile-pure-call [env locals block-id depth form]
  (let [op (call-id (first form))
        sig (ops/signature op)
        args (vec (rest form))]
    (when (not= (count args) (count (:params sig)))
      (report! env {:code :arity-mismatch :form form
                   :message (str (first form) " expects " (count (:params sig)) " args, got " (count args))}))
    (let [arg-regs (mapv (fn [want a]
                           (let [{:keys [reg]} (compile-form env locals block-id depth a false)
                                 got (type-of env reg)]
                             (when-not (types/assignable? got want)
                               (report! env {:code :type-mismatch :form a :want want
                                            :message (str (first form) " arg wants " want " got " got)}))
                             (coerce! env block-id reg got want)))
                         (:params sig) args)
          dst (alloc-reg! env (types/bank (:returns sig)) (:returns sig))]
      (append! env block-id {:op :pure :nid (nid-for! env form) :dst dst :fn op :args arg-regs})
      {:reg dst :block-id block-id})))

(defn- compile-map-literal
  "{...} in expression position -> a :map-lit instruction. Every value is
   compiled as an ordinary pure expression (never a further call -- see
   compile-form's :else branch for why); keys are used as-is (must already
   be literal EDN values, always keywords in practice). Threads block-id
   through even though none of the values it compiles can currently change
   it (pure-only), for the same uniformity every other multi-value
   compiler here already follows."
  [env locals block-id depth form]
  (let [[resolved block-id]
        (reduce (fn [[acc block-id] [k v-form]]
                  (let [{:keys [reg block-id]} (compile-form env locals block-id depth v-form false)]
                    [(assoc acc k reg) block-id]))
                [{} block-id] form)
        dst (alloc-reg! env :objects :any)]
    (append! env block-id {:op :map-lit :nid (nid-for! env form) :dst dst :args resolved})
    {:reg dst :block-id block-id}))

(defn- compile-vec-literal
  "[...] in expression position -> a :vec-lit instruction, compile-map-
   literal's sibling for ORDERED rather than keyed data (:add-tags [\"x\"],
   :instance-key [:activation :foo] -- both real shapes in ac's ability
   content once entity/spawn and projectile/schedule-beam calls started
   getting converted, S6). A plain [x y z] all-number vector is NOT this:
   literal?/compile-literal! already claim that shape as a folded :vec3
   constant (see this file's own docstring on vec3-literal?), checked
   before compile-form ever reaches this function -- so this only ever
   sees a non-vec3 vector. Every element compiles as an ordinary pure
   expression, same allow-calls?=false rule as compile-map-literal, and
   :args is a VECTOR of registers (order-preserving) rather than a map,
   which cn.li.node.ir/validate-instr! and cn.li.mcmod.runtime.effect-
   emit/compile-args already both handle generically alongside :map-lit's
   map shape -- no format-specific plumbing needed on either side."
  [env locals block-id depth form]
  (let [[resolved block-id]
        (reduce (fn [[acc block-id] v-form]
                  (let [{:keys [reg block-id]} (compile-form env locals block-id depth v-form false)]
                    [(conj acc reg) block-id]))
                [[] block-id] form)
        dst (alloc-reg! env :objects :any)]
    (append! env block-id {:op :vec-lit :nid (nid-for! env form) :dst dst :args resolved})
    {:reg dst :block-id block-id}))

(defn compile-form
  "Compile one expression/call form against `locals` at `block-id`.
   allow-calls?: when false (pure-op args, node/fn call args, when/each
   conditions), a vocab-node or :defn call in head position is a compile
   error; when true (let RHS, bare statement calls), it dispatches to
   compile-call."
  [env locals block-id depth form allow-calls?]
  (cond
    (literal? form) {:reg (compile-literal! env form) :block-id block-id}
    (symbol? form) (compile-symbol env locals block-id form)
    ;; seq?, NOT list?: cn.li.node.pretty reconstructs call forms via
    ;; list*/cons, which produce clojure.lang.Cons/LazySeq -- seq? but not
    ;; list? (list? requires clojure.lang.IPersistentList specifically, the
    ;; type a literal EDN (a b c) reads as, which Cons/LazySeq are not).
    ;; Recompiling unparse's own output must work exactly like compiling
    ;; hand-written source.
    (seq? form)
    (cond
      (keyword? (first form)) (compile-field-access env locals block-id depth form (first form) (second form))
      (symbol? (first form))
      (if (ops/known-op? (call-id (first form)))
        (compile-pure-call env locals block-id depth form)
        (compile-call env locals block-id depth form allow-calls?))
      :else (throw (ex-info "malformed DSL call: head must be a symbol or keyword" {:form form})))
    ;; A literal EDN map appearing where an expression is expected (e.g.
    ;; :policy {:type :penetration :scan-step $x}, mixing a literal :type
    ;; with a dynamic :scan-step) -- opaque :any-typed data the HOST
    ;; interprets, not something node-core's type system decomposes.
    ;; Every value compiles as an ordinary pure expression (never a
    ;; further call: a map literal is data construction, not control
    ;; flow); keys must be literal (always keywords in every real use).
    (map? form) (compile-map-literal env locals block-id depth form)
    ;; Reached only for a non-vec3 vector (literal?/vec3-literal? above
    ;; already claimed the [x y z]-all-numbers shape) -- see
    ;; compile-vec-literal's own docstring.
    (vector? form) (compile-vec-literal env locals block-id depth form)
    :else (throw (ex-info "unsupported DSL form" {:form form}))))

;; --- statement compilation ---------------------------------------------------

(defn- compile-let [env locals block-id depth [_ sym rhs]]
  (let [{:keys [reg block-id]} (compile-form env locals block-id depth rhs true)]
    (when (nil? reg)
      (report! env {:code :void-let-rhs :form rhs
                   :message (str "let " sym " bound to a call with no return value")}))
    (let [reg (or reg (dummy-register! env :any))
          ;; A literal RHS (compile-literal!'s vec3-literal?/number?/etc
          ;; branches) allocates a :const-pool reference, not a mutable
          ;; per-bank slot -- fine for a read-only local, but set! (found
          ;; necessary porting scatter_bomb.edn, S6: a placeholder vec3
          ;; predeclared then reassigned in whichever `if` arm runs)
          ;; needs a real register to write into, and there is no
          ;; structural reason a let-bound local's later reassignability
          ;; should depend on the invisible compiler detail of whether its
          ;; initial value happened to be a literal. Promote unconditionally:
          ;; copy the const into a fresh mutable register so every local is
          ;; uniformly set!-able, matching compile-set!'s own docstring
          ;; ("no structural reason set! should be more restrictive").
          reg (if (= :const (first reg))
                (let [t (type-of env reg)
                      dst (alloc-reg! env (types/bank t) t)]
                  (append! env block-id {:op :copy :nid (nid! env) :dst dst :src reg})
                  dst)
                reg)]
      {:locals (assoc locals sym {:reg reg}) :block-id block-id})))

(defn- compile-when [env locals block-id depth [_ cond-form & body :as stmt]]
  (let [{cond-reg :reg block-id :block-id} (compile-form env locals block-id depth cond-form false)]
    (when-not (types/assignable? (type-of env cond-reg) :boolean)
      (report! env {:code :type-mismatch :form cond-form :want :boolean
                   :message "when condition must be :boolean"}))
    (let [then-id (new-block! env)
          continue-id (new-block! env)]
      (append! env block-id {:op :branch :nid (nid-for! env stmt) :test cond-reg :then then-id :else continue-id})
      ;; continue-id is always reachable via the branch's OWN :else edge
      ;; (the condition-false path), so it is never an empty/unreachable
      ;; block regardless of whether the then-body terminates -- but if it
      ;; DOES terminate (ends in `finish`), appending a :jump after that
      ;; :finish would put a non-terminal instruction after the block's
      ;; real terminator, which ir/validate! rejects. A `when` whose body
      ;; finishes early (a real, common pattern -- "insufficient resource,
      ;; abort") was uncaught by every test so far because none of them
      ;; happened to end a when-body in `finish`.
      (let [{final-then :block-id terminated? :terminated?} (compile-stmts! env locals then-id depth (vec body))]
        (when-not terminated?
          (append! env final-then {:op :jump :nid (nid! env) :target continue-id})))
      {:locals locals :block-id continue-id})))

(defn- compile-if
  "Two-armed: (if cond [then-stmt...] [else-stmt...]) -- bodies are
   vectors, not trailing variadic forms, so the two arms are unambiguous
   (unlike `when`, which only ever needs one body and can use & body).

   The emitted :branch carries a :two-armed? breadcrumb (the SAME
   technique compile-each uses for :loop-hint -- an explicit compiler tag,
   not something cn.li.node.pretty has to infer from block shape) so the
   decompiler can tell an `if` apart from a `when`, which are otherwise
   both just a :branch with :then/:else at the IR level. When both arms
   terminate, :continue is nil and the breadcrumb still lets the
   decompiler print the right form instead of guessing."
  [env locals block-id depth [_ cond-form then-stmts else-stmts :as stmt]]
  (let [{cond-reg :reg block-id :block-id} (compile-form env locals block-id depth cond-form false)]
    (when-not (types/assignable? (type-of env cond-reg) :boolean)
      (report! env {:code :type-mismatch :form cond-form :want :boolean
                   :message "if condition must be :boolean"}))
    (let [then-id (new-block! env) else-id (new-block! env)]
      (let [{then-final :block-id then-terminated? :terminated?}
            (compile-stmts! env locals then-id depth (vec then-stmts))
            {else-final :block-id else-terminated? :terminated?}
            (compile-stmts! env locals else-id depth (vec else-stmts))
            continue-id (when-not (and then-terminated? else-terminated?) (new-block! env))]
        (append! env block-id {:op :branch :nid (nid-for! env stmt) :test cond-reg :then then-id :else else-id
                               :two-armed? true :continue continue-id})
        (when (and continue-id (not then-terminated?))
          (append! env then-final {:op :jump :nid (nid! env) :target continue-id}))
        (when (and continue-id (not else-terminated?))
          (append! env else-final {:op :jump :nid (nid! env) :target continue-id}))
        (if continue-id
          {:locals locals :block-id continue-id}
          ;; Both arms finish -- there is no fall-through at all, so
          ;; unlike `when` there is no :else edge guaranteeing a
          ;; continuation block would ever be reached. No continue-id was
          ;; allocated (ir/validate! rejects a block with zero
          ;; instructions); this `if` itself terminates its enclosing
          ;; statement sequence.
          {:locals nil :block-id then-final :terminated? true})))))

(defn- compile-each
  "Desugars entirely to :pure operations plus :copy/:branch/:jump: iterating
   an already-produced list value is pure/deterministic, so it needs no
   host round-trip. :collection/count, :collection/nth and :long/inc are
   compiler-internal synthetic op names, NOT entries in cn.li.node.ops's
   table -- they are never reachable as a DSL-authored pure-op call (that
   path goes through compile-pure-call, which only recognizes
   ops/known-op? names), only ever emitted here.

   binding is either a bare item symbol, or a [item index] vector when
   the body needs its own position within the collection (S6's
   scatter_bomb.edn: :flow/foreach's old :index-as, used to gate auto-aim
   to only the first N-by-mastery balls fired). index-reg already exists
   as this loop's own internal iteration counter regardless -- exposing
   it as a second bound local is just one more entry in body-locals, read
   before the SAME register gets incremented at the bottom of the loop,
   never written to by DSL-authored code (cn.li.node.pretty's unparse-
   each does not reconstruct the index form yet; round-tripping an
   indexed each is a known, currently-unexercised gap, not a silent
   miscompile -- the shape simply is not seen on the way back out)."
  [env locals block-id depth [_ binding coll-form & body :as stmt]]
  (let [[sym index-sym] (if (vector? binding) binding [binding nil])
        {coll-reg :reg block-id :block-id} (compile-form env locals block-id depth coll-form false)
        coll-type (type-of env coll-reg)
        elem-type (if (types/list-of? coll-type) (second coll-type) :any)
        count-reg (alloc-reg! env :longs :long)
        _ (append! env block-id {:op :pure :nid (nid! env) :dst count-reg :fn :collection/count :args [coll-reg]})
        index-reg (alloc-reg! env :longs :long)
        zero (alloc-const! env :longs :long 0)
        _ (append! env block-id {:op :copy :nid (nid! env) :dst index-reg :src zero})
        header-id (new-block! env)]
    (append! env block-id {:op :jump :nid (nid! env) :target header-id})
    (let [idx-d (coerce! env header-id index-reg :long :double)
          cnt-d (coerce! env header-id count-reg :long :double)
          test-reg (alloc-reg! env :booleans :boolean)
          _ (append! env header-id {:op :pure :nid (nid! env) :dst test-reg :fn :math/lt :args [idx-d cnt-d]})
          body-id (new-block! env)
          after-id (new-block! env)]
      (append! env header-id {:op :branch :nid (nid-for! env stmt) :test test-reg :then body-id :else after-id
                              :loop-hint {:header header-id :index index-reg :collection coll-reg}})
      (let [item-reg (alloc-reg! env (types/bank elem-type) elem-type)
            _ (append! env body-id {:op :pure :nid (nid! env) :dst item-reg :fn :collection/nth :args [coll-reg index-reg]})
            body-locals (cond-> (assoc locals sym {:reg item-reg})
                          index-sym (assoc index-sym {:reg index-reg}))
            {final-body :block-id terminated? :terminated?} (compile-stmts! env body-locals body-id depth (vec body))]
        ;; after-id is always reachable via the header's OWN :else edge
        ;; (the loop's natural exit), so it is never an unreachable/empty
        ;; block regardless of whether the body terminates early -- but if
        ;; it DOES (a `finish` inside an each body, ending the whole
        ;; ability mid-iteration -- unusual but not invalid), the
        ;; increment/back-jump machinery must not be appended after that
        ;; :finish, same reasoning as compile-when's identical fix.
        (when-not terminated?
          (let [next-index (alloc-reg! env :longs :long)]
            (append! env final-body {:op :pure :nid (nid! env) :dst next-index :fn :long/inc :args [index-reg]})
            (append! env final-body {:op :copy :nid (nid! env) :dst index-reg :src next-index})
            (append! env final-body {:op :jump :nid (nid! env) :target header-id}))))
      {:locals locals :block-id after-id})))

(defn- literal-map-value [form]
  (cond
    (keyword? form) form
    (boolean? form) form
    (nil? form) nil
    :else (throw (ex-info "finish fields must be literal keywords/booleans" {:form form}))))

(defn- compile-finish [env block-id [_ fields :as stmt]]
  (append! env block-id
           {:op :finish :nid (nid-for! env stmt)
            :outcome (literal-map-value (:outcome fields))
            :next-phase (some-> (:next-phase fields) literal-map-value)
            :end-ability? (boolean (:end-ability? fields))})
  {:locals nil :block-id block-id :terminated? true})

(defn- compile-state-write [env locals block-id depth [_ key-form value-form :as stmt]]
  (if-not (keyword? key-form)
    (do (report! env {:code :invalid-state-key :form key-form
                      :message "state! key must be a literal keyword"})
        {:locals locals :block-id block-id})
    (let [want (get (:state-types env) key-form)]
      (if (nil? want)
        (do (report! env {:code :unknown-state-key :form key-form
                          :message (str "undeclared state key " key-form)})
            {:locals locals :block-id block-id})
        (let [{:keys [reg block-id]} (compile-form env locals block-id depth value-form false)
              got (type-of env reg)]
          (when-not (types/assignable? got want)
            (report! env {:code :type-mismatch :form value-form :want want
                         :message (str "state! " key-form " wants " want " got " got)}))
          (append! env block-id {:op :state-write :nid (nid-for! env stmt) :key key-form
                                 :src (coerce! env block-id reg got want)})
          {:locals locals :block-id block-id})))))

(defn- compile-set!
  "set! reassigns an EXISTING local (bound by an outer let/each/param) to
   a new value -- the loop-accumulator pattern (each with (let sym) taking a
   sub-collection: energy/budget totals decreasing per iteration, and
   similar) that node-core's IR always physically supported (registers are
   mutable per-bank slots, not true SSA -- see cn.li.node.ir's own
   docstring) but had no DSL statement exposing until porting terrain/
   apply-break-budget (S6) needed exactly this. Implemented as a plain
   :copy into the EXISTING register, tagged :reassign? true so
   cn.li.node.pretty can tell it apart from an ordinary compiler-internal
   :copy (each's index bookkeeping, coerce!'s bank conversions) -- see
   that namespace's inline-op?/stmts-for-range for why pretty-printing a
   :reassign? copy is a deliberate, safe deferral (throws a clear error)
   rather than attempted here."
  [env locals block-id depth [_ sym value-form :as stmt]]
  (if-let [{target-reg :reg} (get locals sym)]
    (let [want (type-of env target-reg)
          ;; allow-calls? true, same as `let`'s own RHS: a reassignment's
          ;; new value is exactly as entitled to come from a query/action
          ;; as a fresh binding's is (S6's scatter_bomb.edn needs a
          ;; kernel/scatter-end call here) -- there is no structural
          ;; reason set! should be more restrictive than let, this was
          ;; simply never exercised with a call-based RHS before.
          {:keys [reg block-id]} (compile-form env locals block-id depth value-form true)
          got (type-of env reg)]
      (when-not (types/assignable? got want)
        (report! env {:code :type-mismatch :form value-form :want want
                     :message (str "set! " sym " wants " want " got " got)}))
      (append! env block-id {:op :copy :nid (nid-for! env stmt) :dst target-reg
                             :src (coerce! env block-id reg got want) :reassign? true})
      {:locals locals :block-id block-id})
    (do (report! env {:code :unbound-local :form sym :message (str "set! target " sym " is not bound")})
        {:locals locals :block-id block-id})))

(defn- compile-event
  "event! is deliberately untyped against any vocab (:type's payload shape
   varies per event, unlike a node call's fixed :params) -- every field
   other than :type compiles as an ordinary pure expression and is passed
   through as-is; there is nothing here for cn.li.node.types to check
   against."
  [env locals block-id depth [_ fields :as stmt]]
  (if-not (keyword? (:type fields))
    (do (report! env {:code :invalid-event-type :form fields
                      :message "event! requires a literal :type keyword"})
        {:locals locals :block-id block-id})
    (let [[resolved block-id]
          (reduce (fn [[acc block-id] [k v-form]]
                    (let [{:keys [reg block-id]} (compile-form env locals block-id depth v-form false)]
                      [(assoc acc k reg) block-id]))
                  [{} block-id] (dissoc fields :type))]
      (append! env block-id {:op :event :nid (nid-for! env stmt) :event-type (:type fields) :args resolved})
      {:locals locals :block-id block-id})))

(defn- compile-vfx
  "vfx! is event!'s sibling: an outbound signal, not a host query/action --
   mcmod.runtime.effect-emit's :vfx op is distinct from :action precisely
   because a VFX signal needs no capability dispatch/preflight, only to be
   appended to the frame's own outbox (see cn.li.combat.dsl-vocabulary's
   :effect/vfx docstring for why it does NOT go through the ordinary
   vocab-node :query/:action mechanism). Requires a literal :effect-id;
   every other field, including :operation, compiles as an ordinary pure
   expression and is passed through in :args."
  [env locals block-id depth [_ fields :as stmt]]
  (if-not (keyword? (:effect-id fields))
    (do (report! env {:code :invalid-vfx-signal :form fields
                      :message "vfx! requires a literal :effect-id keyword"})
        {:locals locals :block-id block-id})
    (let [[resolved block-id]
          (reduce (fn [[acc block-id] [k v-form]]
                    (let [{:keys [reg block-id]} (compile-form env locals block-id depth v-form false)]
                      [(assoc acc k reg) block-id]))
                  [{} block-id] fields)]
      (append! env block-id {:op :vfx :nid (nid-for! env stmt) :args resolved})
      {:locals locals :block-id block-id})))

(defn compile-stmt [env locals block-id depth stmt]
  (when-not (and (seq? stmt) (symbol? (first stmt)))
    (throw (ex-info "a DSL statement must be a list headed by a symbol" {:stmt stmt})))
  (case (first stmt)
    let (let [{:keys [locals block-id]} (compile-let env locals block-id depth stmt)]
          {:locals locals :block-id block-id})
    when (compile-when env locals block-id depth stmt)
    if (compile-if env locals block-id depth stmt)
    each (compile-each env locals block-id depth stmt)
    finish (compile-finish env block-id stmt)
    state! (compile-state-write env locals block-id depth stmt)
    set! (compile-set! env locals block-id depth stmt)
    event! (compile-event env locals block-id depth stmt)
    vfx! (compile-vfx env locals block-id depth stmt)
    (let [{:keys [block-id]} (compile-call env locals block-id depth stmt true)]
      {:locals locals :block-id block-id})))

(defn compile-stmts!
  "Fold compile-stmt over `stmts`, threading block-id forward. Stops early
   (reporting :unreachable-code for anything after) once a statement
   terminates its block (a `finish`, or a nested when/each whose OWN
   compile-stmts! already terminated -- compile-when/compile-each never
   propagate :terminated? themselves since they always resume at a
   continuation block, so only a direct `finish` at this level can end the
   sequence early)."
  [env locals block-id depth stmts]
  (loop [locals locals block-id block-id remaining (seq stmts)]
    (if (empty? remaining)
      {:locals locals :block-id block-id}
      (let [stmt (first remaining)
            result (compile-stmt env locals block-id depth stmt)]
        (if (:terminated? result)
          (do (when (seq (rest remaining))
                (report! env {:code :unreachable-code :form (second remaining)
                             :message "statement after finish is unreachable"}))
              {:locals (:locals result) :block-id (:block-id result) :terminated? true})
          (recur (:locals result) (:block-id result) (rest remaining)))))))

;; --- entry / doc compilation --------------------------------------------------

(defn- compile-entry!
  "Returns the STARTING block id for this phase's program, for :entries --
   NOT the block compilation happened to end on, which for any phase
   containing a when/each is a different block. (A shadowed `let` binding
   here once returned the wrong one; :entries would have pointed every
   branching phase at its own continuation block instead of its actual
   first instruction.)"
  [env stmts]
  (let [start-id (new-block! env)
        {:keys [block-id terminated?]} (compile-stmts! env {} start-id 0 stmts)]
    (when-not terminated?
      (append! env block-id {:op :finish :nid (nid! env) :outcome :ended :next-phase nil :end-ability? false}))
    start-id))

(defn compile-program
  "doc: normalized :ability doc (cn.li.node.surface/normalize).
   opts: {:vocab {node-id spec} :capabilities {cap-key type} :fns {fn-id fn-doc}}
   mode: :throw or :collect.
   Returns {:ir ir-or-nil :diagnostics [...]}. In :throw mode, a compile
   error throws instead of returning; :diagnostics is always [] on success."
  [doc opts mode]
  (when-not (= :ability (:kind doc))
    (throw (ex-info "compile-program expects a normalized :ability doc" {:doc doc})))
  (let [env (new-env (assoc opts :mode mode
                           :tunable-types (into {} (map (fn [[k v]] [k (:type v)])) (:tunables doc))
                           :state-types (into {} (map (fn [[k v]] [k (:type v)])) (:state doc))))
        entries (into {} (map (fn [[phase stmts]] [phase (compile-entry! env stmts)])) (:entries doc))
        ir {:ir/version 1
            :id (:id doc)
            :entry-triggers (:entry-triggers doc)
            ;; Not consumed by cn.li.node.ir/validate! or the emitter -- kept
            ;; on the IR purely so cn.li.node.pretty can reconstruct a
            ;; recompilable :tunables/:state block, :default included (the
            ;; env's OWN :tunable-types/:state-types above are deliberately
            ;; just {k type}, the fast shape every $tunable/%state-key
            ;; type-check actually needs -- these two fields are the richer
            ;; round-trip-only shape, built separately rather than widening
            ;; the hot-path maps to carry a :default nothing at compile time
            ;; reads). :tun/:state-read instructions only carry the key, not
            ;; a default, so this is the IR's only record of either.
            :tunable-types (:tunables doc)
            :state-types (:state doc)
            ;; How many slots per bank a fresh ExecutionFrame needs to run
            ;; this program (cn.li.mcmod.runtime.effect-emit sizes its
            ;; register arrays from this) -- known here for free, since
            ;; alloc-reg! already counted every allocation.
            :registers @(:reg-counters env)
            :constants @(:const-pools env)
            :entries entries
            :blocks (let [registry @(:block-registry env)]
                     (mapv (fn [id] {:id id :instrs (vec @(get registry id))}) (sort (keys registry))))}]
    (if (seq @(:diagnostics env))
      {:ir nil :diagnostics @(:diagnostics env)}
      (do (ir/validate! ir)
          {:ir ir :diagnostics []}))))

(defn compile!
  "Compile `doc` (already normalized) and return the IR, throwing ex-info
   on the first error."
  [doc opts]
  (:ir (compile-program doc opts :throw)))

(defn diagnostics
  "Compile `doc` collecting every error instead of stopping at the first.
   Returns {:ir ir-or-nil :diagnostics [...]}."
  [doc opts]
  (compile-program doc opts :collect))
