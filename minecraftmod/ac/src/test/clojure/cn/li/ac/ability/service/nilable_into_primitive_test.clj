(ns cn.li.ac.ability.service.nilable-into-primitive-test
  "Which field reads can put a nil into a primitive register.

   This engine has exactly one crash mechanism and it is narrow:
   cn.li.mcmod.runtime.effect-emit's :convert throws on nil (and on a
   non-number) when the target is :double or :long, and compile-writer
   throws :nil-primitive-write when a primitive register is written with
   nil. Everything else degrades quietly.

   The gap-C census counted 103 places where an :any-typed value reaches a
   numeric parameter, each of which compiles a :convert. A field read is
   only safe there if the producer can never put nil under that key --
   and naming a key the producer DOES set is not enough, because plenty of
   them are (some-> ... str), (when (map? x) ...) or (or a b c), all of
   which yield nil for ordinary world states.

   So this reports (source type, field) -> numeric target for every such
   site, which is the list that has to be checked against the producing
   expression one by one."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.node.api :as node-api]
            [cn.li.node.types :as types]))

(def ^:private compile-ns (do (require 'cn.li.node.compile) 'cn.li.node.compile))
(def ^:private compile-field-access-var (ns-resolve compile-ns 'compile-field-access))
(def ^:private coerce!-var (ns-resolve compile-ns 'coerce!))

(def ^:private ^:dynamic *skill* nil)

(deftest field-reads-that-reach-a-primitive-register
  (let [origin (atom {})     ; register -> [skill source-type field]
        sites (atom [])
        fa-orig @compile-field-access-var
        co-orig @coerce!-var
        doc-orig node-api/compile-v4-skill-document!]
    (with-redefs-fn
      {#'node-api/compile-v4-skill-document!
       (fn [value opts mode]
         (binding [*skill* (:id value)] (doc-orig value opts mode)))

       compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (fa-orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               g (last (filter #(= :get (:op %)) instrs))]
           (when g
             ;; Keyed by [skill register], not by register alone. A bare
             ;; register key is wrong across a catalog: alloc-reg! numbers
             ;; slots per PROGRAM, so [:reg :objects 5] names a different
             ;; value in each of the 50 skills while this atom lives for
             ;; the whole assemble. That collision is what made the
             ;; per-field breakdown claim reads that the schemas prove
             ;; cannot exist -- :owner-snapshot's :velocity is declared
             ;; :vec3, so a :velocity read can never coerce FROM :any.
             (swap! origin assoc [*skill* (:dst g)]
                    [(types/canonical-type (get @(:reg-types env) (:src g))) k]))
           result))

       coerce!-var
       (fn [env block-id reg from to]
         (let [to* (types/canonical-type to)]
           (when (and (= :any (types/canonical-type from))
                      (contains? #{:doubles :longs} (types/bank to*))
                      (contains? @origin [*skill* reg]))
             (let [[src field] (get @origin [*skill* reg])]
               (swap! sites conj {:skill *skill* :source src :field field :target to*}))))
         (co-orig env block-id reg from to))}
      #(skills-catalog/assemble {:mode :collect}))

    (let [by-pair (frequencies (map (juxt :source :field :target) @sites))]
      (println)
      (println "=== field reads reaching a :double/:long parameter ===")
      (println "sites:" (count @sites))
      (println)
      (println "(source type, field) -> target   [each needs its producing")
      (println " expression checked: can it yield nil?]")
      (doseq [[[s f t] n] (sort-by (comp - val) by-pair)]
        (println (format "  %4d  %-20s %-24s -> %s" n (pr-str s) (pr-str f) (pr-str t))))
      (println)
      (println "skills involved:" (pr-str (sort (distinct (map :skill @sites)))))
      (println)

      ;; A ratchet, not a clean bill of health. These are pre-existing and
      ;; unverified: proving any single one throws needs the producing
      ;; expression traced by hand, and proving it does NOT needs the same.
      ;; What the number buys is the NEXT one -- adding a field read into a
      ;; numeric parameter now fails here and has to be justified.
      ;;
      ;; 83 -> 50 when :context/resources stopped being :any. That one
      ;; capability was 33 of the 83 on its own, which is the shape of this
      ;; whole problem: the sites are not scattered, they cluster behind a
      ;; handful of untyped SOURCES, and typing a source retires a whole
      ;; group at once. The largest cluster left is :hardness (9), still
      ;; read off an :any for the same reason the pool's amounts were.
      ;;
      ;; 50 -> 28 with no production change at all: the attribution below
      ;; was keyed by bare register, and slots are numbered per PROGRAM
      ;; while the atom lives for the whole 50-skill assemble, so every
      ;; skill's [:reg :objects 5] collided with every other's.
      ;;
      ;; That bug invented rows, and invented exactly the alarming kind.
      ;; It had claimed :owner-snapshot's :velocity -- a {:x :y :z} map --
      ;; reached a :double parameter six times, which would throw on every
      ;; execution rather than on an unlucky world state. It cannot: that
      ;; field is declared :vec3, so the coercion it was attributed to
      ;; could never have been FROM :any. The same goes for the seven
      ;; :position reads, :entity-id, :damage-type and :available?. All 22
      ;; were collisions. Reading the schemas is what settled it, which is
      ;; the argument for declaring them: a typed field makes a false claim
      ;; about itself checkable.
      ;;
      ;; What is left is coherent -- every row is a genuinely numeric field
      ;; whose producer has simply not been checked yet, so each is an
      ;; honest nil-safety question rather than noise. Typing one is the
      ;; same explicit decision :resource-pool already went through: say
      ;; whether the producing expression can return nil, then either
      ;; declare it and whitelist it with the reason, or fix the producer.
      (is (= 28 (count @sites))
          (str "field reads reaching a numeric parameter changed. Each is a"
               " potential :convert throw; justify a new one or remove it: "
               (pr-str (sort-by (juxt :skill :field) @sites)))))))
