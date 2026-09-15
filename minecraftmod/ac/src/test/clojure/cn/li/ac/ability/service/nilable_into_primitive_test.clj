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
             (swap! origin assoc (:dst g)
                    [*skill* (types/canonical-type (get @(:reg-types env) (:src g))) k]))
           result))

       coerce!-var
       (fn [env block-id reg from to]
         (let [to* (types/canonical-type to)]
           (when (and (= :any (types/canonical-type from))
                      (contains? #{:doubles :longs} (types/bank to*))
                      (contains? @origin reg))
             (let [[skill src field] (get @origin reg)]
               (swap! sites conj {:skill skill :source src :field field :target to*}))))
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

      ;; A ratchet, not a clean bill of health. These 83 are pre-existing and
      ;; unverified: proving any single one throws needs the producing
      ;; expression traced by hand, and proving it does NOT needs the same.
      ;; What the number buys is the 84th -- adding a new field read into a
      ;; numeric parameter now fails here and has to be justified.
      ;;
      ;; Worth triaging by eye, because several pairs are not nilability at
      ;; all but a type confusion that throws on EVERY execution rather than
      ;; on an unlucky world state -- effect-emit's :convert rejects a
      ;; non-number as hard as it rejects nil:
      ;;
      ;;   :hit-result :entity-id -> :double     an id, never numeric
      ;;   :hit-result :entity-type -> :double   a type string
      ;;   :any :damage-type -> :double          a keyword
      ;;   :any :tool-tier-capped? -> :double    a boolean
      ;;   :owner-snapshot :velocity -> :double  a {:x :y :z} map
      ;;   :hit-result/:owner-snapshot/:block-placement :position -> :double
      ;;
      ;; The register-origin attribution has one known limit: cn.li.node.ir
      ;; registers are reused across `set!` (a :reassign? copy into the
      ;; existing slot), so a slot recorded as a field read can later hold
      ;; something else. 17 :assign nodes exist across 5 skills, so the
      ;; contamination is bounded but not zero -- treat an individual row as
      ;; a lead, not a verdict.
      (is (= 83 (count @sites))
          (str "field reads reaching a numeric parameter changed. Each is a"
               " potential :convert throw; justify a new one or remove it: "
               (pr-str (sort-by (juxt :skill :field) @sites)))))))
