(ns cn.li.ac.ability.service.gap-c-source-census-test
  "G0.2 of the gap-C plan: where does an :any that REACHES a typed parameter
   come from?

   The G0 census (gap-c-census-test) counts assignments the `from :any` rule
   permitted, classified by target: 290 in skills-v4, of which 137 land in
   the :objects bank where nothing checks them at all. It cannot say why the
   source was :any, because assignable? receives only (from, to). But the
   phases that would shrink the problem each eliminate a different SOURCE:

     parametric ops    :collection/first & friends return :any because
                       cn.li.node.ops signatures are monomorphic
     literals          map/vec literals allocate :objects :any
     field reads       a field with no schema entry stays :any
     declarations      a parameter or capability declared :any

   Two instrumented functions, joined:

     alloc-reg!  decides a register's type -- wrapping it gives
                 register -> the compile-* construct that created it,
                 attributed via the call stack (crude, but it is the only
                 thing that tells `(:field x)` from `(some/op x)` from
                 `{...}` at the point the type is chosen).
     coerce!     runs at every argument assignment and receives BOTH the
                 source register and (from, to). It is the join: it turns
                 \"an :any reached a :vec3\" into \"a MAP LITERAL reached a
                 :vec3\".

   Supply is not demand, which is why the join matters: most :any registers
   never reach a typed parameter at all. Counting allocations alone would
   order the work by the wrong number.

   Behaviour is unchanged -- both wrappers delegate and only count."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.node.types :as types]))

;; ns-resolve, not #' -- both are private to cn.li.node.compile and a plain
;; var-quote from here would not resolve them.
(def ^:private compile-ns (do (require 'cn.li.node.compile) 'cn.li.node.compile))
(def ^:private alloc-reg!-var (ns-resolve compile-ns 'alloc-reg!))
(def ^:private coerce!-var (ns-resolve compile-ns 'coerce!))
(def ^:private compile-field-access-var (ns-resolve compile-ns 'compile-field-access))

(defn- demunge-frame
  "A Clojure fn's generated class name -> the source fn name.
   cn.li.node.compile$compile_field_access -> \"compile-field-access\"."
  [^String cls]
  (-> cls
      (str/replace #"^cn\.li\.node\.compile\$" "")
      (str/replace #"__\d+$" "")
      (str/replace "_BANG_" "!")
      (str/replace "_QMARK_" "?")
      (str/replace "_" "-")))

(defn- allocating-construct []
  (->> (.getStackTrace (Thread/currentThread))
       (map #(.getClassName ^StackTraceElement %))
       (filter #(str/starts-with? % "cn.li.node.compile$"))
       (remove #(str/includes? % "alloc-reg"))
       (remove #(str/includes? % "alloc_reg"))
       (remove #(str/includes? % "alloc_const"))
       first
       (#(if % (demunge-frame %) "<unknown>"))))

(deftest gap-c-any-source-reaching-a-typed-parameter-census
  (let [origin (atom {})        ; register -> construct that allocated it
        reaching (atom {})      ; [construct target-bank] -> n
        by-pair (atom {})       ; [construct target-type] -> n
        alloc-orig @alloc-reg!-var
        coerce-orig @coerce!-var]
    (with-redefs-fn
      {alloc-reg!-var
       (fn [env bank type]
         (let [r (alloc-orig env bank type)]
           (when (= :any type)
             (swap! origin assoc r (allocating-construct)))
           r))

       coerce!-var
       (fn [env block-id reg from to]
         (let [from* (types/canonical-type from)
               to* (types/canonical-type to)]
           (when (and (= :any from*) (not= :any to*))
             (let [construct (get @origin reg "<not-an-any-allocation>")]
               (swap! reaching update [construct (types/bank to*)] (fnil inc 0))
               (swap! by-pair update [construct to*] (fnil inc 0)))))
         (coerce-orig env block-id reg from to))}
      #(skills-catalog/assemble {:mode :collect}))

    (let [total (reduce + 0 (vals @reaching))
          by-construct (reduce (fn [acc [[c _] n]] (update acc c (fnil + 0) n)) {} @reaching)
          objects-only (reduce (fn [acc [[c b] n]]
                                 (if (= :objects b) (update acc c (fnil + 0) n) acc))
                               {} @reaching)]
      (println)
      (println "=== gap-C G0.2: :any values that REACH a typed parameter ===")
      (println "total:" total)
      (println)
      (println "by allocating construct (all target banks):")
      (doseq [[c n] (sort-by (comp - val) by-construct)]
        (println (format "  %6d  %s" n c)))
      (println)
      (println "*** the 137-site :objects subset -- this round's actual target ***")
      (doseq [[c n] (sort-by (comp - val) objects-only)]
        (println (format "  %6d  %s" n c)))
      (println)
      (println "construct -> target type (top 20):")
      (doseq [[[c t] n] (take 20 (sort-by (comp - val) @by-pair))]
        (println (format "  %6d  %-24s -> %s" n c (pr-str t))))
      (println)
      (is (map? @reaching)))))

(deftest gap-c-untyped-field-read-worklist
  "The work order for the field-schema phase: which (source type, field)
   pairs still read as :any.

   G0.2 says field reads are 73% of the problem but not WHICH fields, and a
   schema entry needs both halves -- :position is :vec3 on a :hit-result
   and something else on a type that happens to share the name.

   Recovered from the compiler's own output rather than guessed: after
   compile-field-access runs it has appended a :get instruction whose :src
   is the register the source expression produced, so the source type is
   whatever :reg-types recorded for it. No re-compilation, no inference
   duplicated here."
  (let [pairs (atom {})
        orig @compile-field-access-var]
    (with-redefs-fn
      {compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               get-instr (last (filter #(= :get (:op %)) instrs))
               types* @(:reg-types env)
               src-type (get types* (:src get-instr))
               dst-type (get types* (:dst get-instr))]
           (when (and get-instr (= :any (types/canonical-type dst-type)))
             (swap! pairs update [src-type k] (fnil inc 0)))
           result))}
      #(skills-catalog/assemble {:mode :collect}))

    (let [total (reduce + 0 (vals @pairs))
          by-src (reduce (fn [acc [[s _] n]] (update acc s (fnil + 0) n)) {} @pairs)]
      (println)
      (println "=== gap-C: field reads still producing :any ===")
      (println "total:" total)
      (println)
      (println "by SOURCE type -- a type with many is one missing schema entry:")
      (doseq [[s n] (sort-by (comp - val) by-src)]
        (println (format "  %6d  %s" n (pr-str s))))
      (println)
      (println "top (source type, field) pairs -- this is the worklist:")
      (doseq [[[s k] n] (take 30 (sort-by (comp - val) @pairs))]
        (println (format "  %5d  %-20s %s" n (pr-str s) (pr-str k))))
      (println)
      (is (map? @pairs)))))
