(ns cn.li.node.kernel
  "Compile-time-shared VM plumbing for the domain engines (combat-core,
   vfx-core): the exact ref/expr/collection resolution semantics and
   control-flow shapes every engine needs, expanded as private functions
   INSIDE the calling namespace via macros instead of factored into a
   cross-namespace function call.

   Why macros, not functions: node-core/combat-core/vfx-core are all
   source-first (compileClojure disabled -- see build.gradle) with no
   direct linking, so a cross-namespace defn call from a hot per-node
   resolution path is a real Var deref + IFn.invoke, not something the JIT
   can inline away. resolve-value runs hundreds of times per ability per
   tick; these macros pay the abstraction's cost once, at macroexpansion
   time, not once per node per tick. Each domain VM still owns its own
   dispatch table (:flow/sequence etc for combat, :vfx/group etc for vfx)
   -- only the recursive ref/expr resolution shape is shared, since that
   shape genuinely doesn't vary by domain, only by which context scopes
   exist.

   Scope note: this namespace intentionally covers only ref/expr/collection
   resolution (defresolver). combat-core's actual :flow/foreach reads
   :limit as a raw field (not resolved) with a contracts/budgets-derived
   default and doesn't restore locals after the loop -- a real divergence
   from node-core's original run-foreach, not just a naming difference.
   Folding that into a shared macro needs its own careful audit against
   combat's live dispatch; deferred rather than guessed at here."
  (:require [cn.li.node.expr :as expr]))

(defmacro defresolver
  "Define `name` as a private [value ctx] -> resolved-value function in the
   calling namespace: the {:ref [...]}/{:expr ...}/collection resolution
   dispatch every domain VM needs, generated inline so it compiles to a
   self-recursive local fn, not a call through this namespace.

   opts (a compile-time literal map; every value below except :local/:coll/
   :lerp? is a raw expression spliced in place, free to reference `ctx-sym`):

     :scopes {scope-kw <root expression>}
              One entry per valid first element of a {:ref [scope key & path]}
              vector; `case`s on the scope keyword to pick the resolution root.
     :local   Which scope in :scopes uses \"look up key, then get-in path\"
              semantics (cn.li.node.value's [:local name & path] convention).
              Every other scope uses (get-in root (into [key] path)).
     :seed    Expression producing the seed passed to expr/evaluate; may have
              side effects (e.g. advancing a mutable seed atom) and is
              evaluated fresh at every {:expr ...} node.
     :extras  Expression producing the extra-ops map passed to expr/evaluate's
              4-arity (domain-specific opcodes beyond node-core's built-ins).
     :coll    Compile-time set of collection shapes to recurse into, any of
              #{:map :vector :set}.
     :lerp?   When true, also resolve {:from _ :to _} by linearly
              interpolating at (:progress ctx)."
  [name ctx-sym {:keys [scopes local seed extras coll lerp?]}]
  (let [value-sym (gensym "value")
        scope-sym (gensym "scope")
        key-sym (gensym "key")
        path-sym (gensym "path")
        root-sym (gensym "root")
        k-sym (gensym "k")
        v-sym (gensym "v")
        scope-clauses (mapcat (fn [[scope-kw root-expr]] [scope-kw root-expr]) scopes)]
    `(defn- ~name [~value-sym ~ctx-sym]
       (cond
         (and (map? ~value-sym) (vector? (:ref ~value-sym)))
         (let [[~scope-sym ~key-sym & ~path-sym] (:ref ~value-sym)
               ~root-sym (case ~scope-sym ~@scope-clauses nil)]
           (if (= ~local ~scope-sym)
             (if (seq ~path-sym) (get-in (get ~root-sym ~key-sym) ~path-sym) (get ~root-sym ~key-sym))
             (get-in ~root-sym (into [~key-sym] ~path-sym))))

         (and (map? ~value-sym) (keyword? (:expr ~value-sym)))
         (expr/evaluate (:expr ~value-sym)
                        (mapv #(~name % ~ctx-sym) (:args ~value-sym))
                        ~seed
                        ~extras)

         ~@(when lerp?
             [`(and (map? ~value-sym) (contains? ~value-sym :from) (contains? ~value-sym :to))
              `(let [t# (double (or (:progress ~ctx-sym) 0.0))
                     from# (double (or (~name (:from ~value-sym) ~ctx-sym) 0.0))
                     to# (double (or (~name (:to ~value-sym) ~ctx-sym) 0.0))]
                 (+ from# (* t# (- to# from#))))])

         (map? ~value-sym)
         (into {} (map (fn [[~k-sym ~v-sym]] [~k-sym (~name ~v-sym ~ctx-sym)]) ~value-sym))

         ~@(when (contains? coll :vector)
             [`(vector? ~value-sym) `(mapv #(~name % ~ctx-sym) ~value-sym)])

         ~@(when (contains? coll :set)
             [`(set? ~value-sym) `(set (map #(~name % ~ctx-sym) ~value-sym))])

         :else ~value-sym))))
