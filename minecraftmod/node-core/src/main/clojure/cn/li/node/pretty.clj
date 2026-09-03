(ns cn.li.node.pretty
  "IR -> DSL doc-map, the inverse of cn.li.node.compile/compile-program.
   This is the round-trip path a future graph editor needs: edit the IR,
   print it back to reviewable/diffable DSL text, reload.

   Reconstructs exactly the structural patterns cn.li.node.compile itself
   emits:
     - a :branch whose :else targets a fresh, never-otherwise-shared
       continuation block -> `when`
     - a :branch carrying compile-each's :loop-hint breadcrumb -> `each`,
       STRAIGHT-LINE BODY ONLY (see unparse-each's docstring) -- nested
       when/each inside an each body is a real scope limit of this first
       pass, not a silent miscompile: it throws a clear ex-info instead of
       guessing.

   Variable names are not recoverable from IR -- by the time compile.clj
   is done with a `let`/`each` binding, only an anonymous register remains.
   Reconstructed bindings get fresh synthetic names (v1, v2, item1, ...)
   assigned deterministically in instruction order. That determinism is
   exactly what makes re-unparsing a RECOMPILED result a fixed point
   (unparse . compile . unparse == unparse) even though the printed text
   will not literally match hand-written source."
  (:require [cn.li.node.types :as types]))

;; --- read-only IR indices --------------------------------------------------

(defn- op-sym [kw] (symbol (namespace kw) (name kw)))

(defn- sigil-sym
  "prefix + a possibly-namespaced key keyword, e.g. \"?\" + :caster/eye ->
   the symbol ?caster/eye. (str kw) keeps the namespace (unlike name, which
   would silently drop it -- see cn.li.node.surface/sigil's docstring for
   the bug this once caused here); subs 1 strips (str kw)'s leading \":\"."
  [prefix kw]
  (symbol (str prefix (subs (str kw) 1))))

(defn- const-value [ir [_ bank slot]]
  (let [v (get-in ir [:constants bank slot])]
    (if (and (map? v) (vector? (:vec3 v))) (mapv double (:vec3 v)) v)))

(defn- producer-index [ir]
  (into {} (keep (fn [i] (when (:dst i) [(:dst i) i]))) (mapcat :instrs (:blocks ir))))

;; --- pure-expression reconstruction ----------------------------------------
;;
;; Only :pure/:get/:tun/:cap/:copy/:convert registers are reconstructed
;; inline, unconditionally (not gated on a consumer count): all six are
;; side-effect-free/deterministic-per-activation, so printing the same
;; reconstructed expression at more than one use site is semantically safe
;; -- just not maximally compact. A :query/:action result is ALWAYS looked
;; up in `let-names` instead (bound by stmts-for-range below before any
;; later instruction can reference it -- registers are defined before use
;; by construction).

(defn- reconstruct [ir pidx let-names reg]
  (cond
    (= :const (first reg)) (const-value ir reg)
    (contains? let-names reg) (get let-names reg)
    :else
    (let [instr (get pidx reg)]
      (case (:op instr)
        :pure (cons (op-sym (:fn instr)) (map #(reconstruct ir pidx let-names %) (:args instr)))
        :get (list (:key instr) (reconstruct ir pidx let-names (:src instr)))
        :tun (sigil-sym "$" (:key instr))
        :cap (sigil-sym "?" (:key instr))
        (:copy :convert) (reconstruct ir pidx let-names (:src instr))
        (throw (ex-info "cannot reconstruct expression for this register"
                        {:reg reg :instr instr}))))))

;; --- statement-range printing -----------------------------------------------

(defn- inline-op? [op] (contains? #{:pure :get :tun :cap :copy :convert} op))

(defn- fresh-sym! [counter prefix] (symbol (str prefix (swap! counter inc))))

(defn- call-form [ir pidx let-names node-id args]
  (list* (op-sym node-id)
         [(into {} (map (fn [[k r]] [k (reconstruct ir pidx let-names r)])) args)]))

(defn- stmts-for-range
  "Print every :query/:action in `instrs` as a statement (a `let` when it
   has a :dst, a bare call otherwise); everything else is a pure/copy/
   convert producer captured lazily by reconstruct at its use site, never
   printed on its own. Mutates `let-names*`/`counter` (both atoms local to
   one top-level unparse call) as query/action results are bound."
  [ir pidx let-names* counter instrs]
  (vec
   (keep
    (fn [instr]
      (when-not (inline-op? (:op instr))
        (case (:op instr)
          :query (let [sym (fresh-sym! counter "v")
                      form (call-form ir pidx @let-names* (:node instr) (:args instr))]
                  (swap! let-names* assoc (:dst instr) sym)
                  (list 'let sym form))
          :action (call-form ir pidx @let-names* (:node instr) (:args instr))
          nil)))
    instrs)))

(defn- finish-form [instr]
  (list 'finish
        (cond-> {:outcome (:outcome instr)}
          (:next-phase instr) (assoc :next-phase (:next-phase instr))
          (:end-ability? instr) (assoc :end-ability? true))))

;; --- control-flow reconstruction ---------------------------------------------

(declare unparse-from)

(defn- unparse-when [ir pidx let-names* counter blocks-by-id branch stop-at]
  (let [cond-form (reconstruct ir pidx @let-names* (:test branch))
        then-id (:then branch)
        continue-id (:else branch)
        then-stmts (unparse-from ir pidx let-names* counter blocks-by-id then-id continue-id)
        rest-stmts (unparse-from ir pidx let-names* counter blocks-by-id continue-id stop-at)]
    (into [(list* 'when cond-form then-stmts)] rest-stmts)))

(defn- unparse-each
  "STRAIGHT-LINE BODY ONLY: assumes body-id's own trailing three
   instructions are exactly the [:pure :long/inc] [:copy] [:jump] triad
   compile-each appends after compiling the body -- true whenever the body
   is a flat statement sequence, false the moment the body itself contains
   a nested when/each (compile-each appends that triad to whatever block
   the NESTED control flow eventually lands on, not to body-id itself).
   Detecting and correctly walking that general case is deferred -- this
   throws a clear error instead of silently mis-printing a nested-loop
   ability, which the S1 test corpus does not contain."
  [ir pidx let-names* counter blocks-by-id branch stop-at]
  (let [{:keys [collection]} (:loop-hint branch)
        body-id (:then branch)
        after-id (:else branch)
        body-instrs (vec (:instrs (get blocks-by-id body-id)))
        item-instr (first body-instrs)
        tail (subvec body-instrs (- (count body-instrs) 3))]
    (when-not (and (= :pure (:op item-instr)) (= :collection/nth (:fn item-instr))
                  (= [:pure :copy :jump] (mapv :op tail)))
      (throw (ex-info "each body contains nested control flow; pretty-printing that shape is not yet supported"
                      {:body-block body-id})))
    (let [item-sym (fresh-sym! counter "item")
          _ (swap! let-names* assoc (:dst item-instr) item-sym)
          inner (subvec body-instrs 1 (- (count body-instrs) 3))
          body-stmts (stmts-for-range ir pidx let-names* counter inner)
          coll-form (reconstruct ir pidx @let-names* collection)]
      (swap! let-names* dissoc (:dst item-instr))
      (into [(list* 'each item-sym coll-form body-stmts)]
            (unparse-from ir pidx let-names* counter blocks-by-id after-id stop-at)))))

(defn- unparse-from
  ([ir pidx let-names* counter blocks-by-id block-id]
   (unparse-from ir pidx let-names* counter blocks-by-id block-id nil))
  ([ir pidx let-names* counter blocks-by-id block-id stop-at]
   (if (= block-id stop-at)
     []
     (let [instrs (:instrs (get blocks-by-id block-id))
           terminator (last instrs)
           body-stmts (stmts-for-range ir pidx let-names* counter (butlast instrs))]
       (case (:op terminator)
         :finish (conj body-stmts (finish-form terminator))
         :jump (into body-stmts (unparse-from ir pidx let-names* counter blocks-by-id (:target terminator) stop-at))
         :branch (into body-stmts
                       (if (:loop-hint terminator)
                         (unparse-each ir pidx let-names* counter blocks-by-id terminator stop-at)
                         (unparse-when ir pidx let-names* counter blocks-by-id terminator stop-at))))))))

;; --- top level ---------------------------------------------------------------

(defn unparse
  "IR -> normalized doc-map (cn.li.node.surface/normalize shape)."
  [ir]
  (let [pidx (producer-index ir)
        blocks-by-id (into {} (map (juxt :id identity)) (:blocks ir))
        entries (into {}
                      (map (fn [[phase block-id]]
                             (let [let-names* (atom {}) counter (atom 0)]
                               [phase (vec (unparse-from ir pidx let-names* counter blocks-by-id block-id))])))
                      (:entries ir))
        tunables (into {} (map (fn [[k t]] [k {:type t}])) (:tunable-types ir))]
    {:kind :ability :id (:id ir) :tunables tunables :entries entries}))
