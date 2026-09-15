(ns cn.li.ac.ability.service.nil-field-read-sites-test
  "Locate the field reads that are known to evaluate to nil.

   Two were found by the gap-C measurements, both instances of the same
   thing: a field name that exists on one host record and not on its
   neighbour, with nothing able to say so while both were :any.

     (:position x) where x is :entity-snapshot
       entity-snapshot! builds {:id :type :entity-type :x :y :z :eye-height
       :eye-position :alive?}. No :position. platform/project-entity, behind
       :target/entities, DOES build :position {:x :y :z}.

     (:invulnerable-time x) where x is an element of :target/entities
       project-entity's values map has no such key either.

   This reports skill id and node nid for each, so the repair can be made
   against specific graphs rather than by search-and-hope."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.node.api :as node-api]
            [cn.li.node.types :as types]))

(def ^:private compile-ns (do (require 'cn.li.node.compile) 'cn.li.node.compile))
(def ^:private compile-field-access-var (ns-resolve compile-ns 'compile-field-access))

(def ^:private ^:dynamic *skill* nil)

(def ^:private missing-field?
  "[source-type field] pairs verified absent from the producing function.

   EMPTY, and that is the point of keeping the test: both entries it was
   opened with are fixed, and the set is where a newly discovered one goes.

     [:entity-snapshot :position]       entity-snapshot! now builds
       :position {:x :y :z} the way project-entity spells it, and keeps it
       through a projection. Removed seven sites.
     [:entity-ref :invulnerable-time]   project-entity now forwards the
       :invulnerable-time every mc-* world-effects-core already supplies.
       Removed the eighth.

   The second one was nearly written off here as \"no host-side answer\".
   That was wrong, and checking the upstream implementation is what showed
   it: main's light_shield.clj gates on `(<= (long (or (:invulnerable-time
   entity) 0)) 0)`, so the value exists and is meant to be read -- only the
   projection layer was dropping it."
  #{})

(deftest nil-field-read-sites
  (let [sites (atom [])
        orig @compile-field-access-var
        compile-doc-orig node-api/compile-v4-skill-document!]
    (with-redefs-fn
      {#'node-api/compile-v4-skill-document!
       (fn [value opts mode]
         (binding [*skill* (:id value)]
           (compile-doc-orig value opts mode)))

       compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               get-instr (last (filter #(= :get (:op %)) instrs))
               src-type (types/canonical-type (get @(:reg-types env) (:src get-instr)))]
           (when (and get-instr (missing-field? [src-type k]))
             (swap! sites conj {:skill *skill* :nid (:nid get-instr)
                                :source src-type :field k}))
           result))}
      #(skills-catalog/assemble {:mode :collect}))

    (println)
    (println "=== field reads that evaluate to nil at runtime ===")
    (println "total:" (count @sites))
    (println)
    (doseq [[[source field] group] (sort-by key (group-by (juxt :source :field) @sites))]
      (println (format "(%s %s)  -- %d site(s)" (pr-str field) (pr-str source) (count group)))
      (doseq [{:keys [skill nid]} (sort-by (juxt :skill :nid) group)]
        (println (format "    %-24s %s" (pr-str skill) (pr-str nid))))
      (println))

    ;; An assertion, not just a report: these are known and unrepaired. If
    ;; the count changes, either someone fixed one (lower the number and say
    ;; which) or a new one was authored (fix it). Either way it should not
    ;; pass silently -- a nil-valued read is exactly the class of defect the
    ;; whole type-contract effort exists to stop being invisible.
    (is (= 0 (count @sites))
        (str "known nil-valued field reads changed: " (pr-str @sites)))))
