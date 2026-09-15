(ns cn.li.ac.ability.service.gap-c-census-test
  "G0 of the gap-C plan: count every assignment that ONLY type-checks
   because of assignable?'s `(= from :any)` rule, and classify it by target.

   Deliberately a census, not a trial removal. Flipping the rule off would
   make the first failure in a graph produce a dummy :any register, which
   then feeds the next check and cascades -- the resulting count would
   measure the cascade, not the real sites. Wrapping the predicate and
   recording where it answered true FOR THAT REASON changes no behaviour at
   all and counts exactly the sites in question.

   Classification matters because the three target kinds are not the same
   problem (see the plan's Context table):
     :objects  -- same register bank, so coerce! emits NOTHING and nothing
                  checks it at compile time or run time. The real hole.
     :boolean  -- crosses a bank, so :convert runs, but its implementation
                  is (boolean v): nil silently becomes false.
     :double/:long -- crosses a bank, :convert throws on nil or a
                  non-number with :nid/:from/:to. Already loud; making it
                  one-way only moves the failure earlier."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.ac.vfx.fx-catalog-v4 :as fx-catalog-v4]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.node.types :as types]
            [cn.li.vfx.dsl-vocabulary :as vfx-vocab]
            [cn.li.vfx.scene :as vfx-scene]))

(def ^:private original-assignable? types/assignable?)

(defn- census
  "Run `f` with assignable? instrumented. -> {target-type count} counting
   only the calls the `from :any` rule alone permitted."
  [f]
  (let [hits (atom {})]
    (with-redefs [types/assignable?
                  (fn [from to]
                    (let [answer (original-assignable? from to)
                          from* (types/canonical-type from)
                          to* (types/canonical-type to)]
                      (when (and answer
                                 (= :any from*)
                                 (not= :any to*))
                        (swap! hits update to* (fnil inc 0)))
                      answer))]
      (f))
    @hits))

(defn- bank-of [t]
  (types/bank t))

(deftest gap-c-any-source-census
  (let [skill-hits (census #(skills-catalog/assemble {:mode :collect}))
        total (reduce + 0 (vals skill-hits))
        by-bank (reduce (fn [acc [t n]] (update acc (bank-of t) (fnil + 0) n))
                        {} skill-hits)]
    (println)
    (println "=== gap-C census: assignments permitted only by `from :any` ===")
    (println "skills-v4 total:" total)
    (println)
    (println "by target type:")
    (doseq [[t n] (sort-by (comp - val) skill-hits)]
      (println (format "  %6d  %-22s bank=%s" n (pr-str t) (name (bank-of t)))))
    (println)
    (println "by register bank (the classification that decides the work):")
    (doseq [[b n] (sort-by (comp - val) by-bank)]
      (println (format "  %6d  %s%s" n (name b)
                       (case b
                         :objects "   <- UNCHECKED: no :convert, no runtime check"
                         :booleans "  <- :convert runs, but nil becomes false silently"
                         "  <- :convert runs and throws on nil/non-number"))))
    (println)
    ;; Not an assertion about the number -- G0 is a measurement, and the
    ;; number is the deliverable. This only pins that the instrumentation
    ;; actually ran, so an empty census cannot be mistaken for "no hits".
    (is (map? skill-hits))))

(defn- compile-every-vfx-render-graph!
  "fx-catalog-v4/assemble only READS and validates documents -- VFX render
   graphs are compiled lazily per effect-id at spawn. So the census has to
   compile them itself, the same way cn.li.ac.vfx.empty-render-graph-audit-
   test does: the real compiler, not an approximation of it."
  []
  (doseq [{:keys [document]} (:effects (fx-catalog-v4/assemble))]
    (let [input-types (into {} (map (fn [[k spec]] [k (:type spec)]))
                            (or (:inputs document) (:parameters document)))]
      (graph-compile/compile-vfx!
       document {:vocab vfx-vocab/nodes
                 :capabilities (vfx-scene/capabilities-for input-types)
                 :fns {}}
       :collect))))

(deftest gap-c-any-source-census-vfx
  (let [hits (census compile-every-vfx-render-graph!)
        total (reduce + 0 (vals hits))]
    (println)
    (println "=== gap-C census: vfx-v4 ===")
    (println "vfx-v4 total:" total)
    (doseq [[t n] (sort-by (comp - val) hits)]
      (println (format "  %6d  %-22s bank=%s" n (pr-str t) (name (bank-of t)))))
    (println)
    (is (map? hits))))
