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
        doc-orig node-api/compile-surface-document!]
    (with-redefs-fn
      {#'node-api/compile-surface-document!
       (fn [value opts mode]
         (binding [*skill* (:id value)] (doc-orig value opts mode)))

       compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (fa-orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               g (last (filter #(= :get (:op %)) instrs))]
           (when g
             ;; Keyed by the compile env's own identity, not by register
             ;; and not by [skill register]. alloc-reg! numbers slots per
             ;; PROGRAM while this atom lives for the whole assemble, so a
             ;; bare register key collides across all 50 skills -- and a
             ;; [skill register] key still collides within one skill,
             ;; because a document compiles a separate program per entry
             ;; point and each restarts numbering. Both versions of that
             ;; bug invented rows: the first attributed reads to fields
             ;; whose declared types prove they cannot coerce from :any,
             ;; the second reported :hardness in two skills whose EDN does
             ;; not contain the word.
             ;;
             ;; :reg-types is an atom created once per env, so it is a
             ;; cheap exact identity for "this program" without the
             ;; compiler having to expose one.
             (swap! origin assoc [(:reg-types env) (:dst g)]
                    [(types/canonical-type (get @(:reg-types env) (:src g)))
                     k (:nid g) *skill* (pr-str sub-form)]))
           result))

       coerce!-var
       (fn [env block-id reg from to]
         (let [to* (types/canonical-type to)]
           (when (and (= :any (types/canonical-type from))
                      (contains? #{:doubles :longs} (types/bank to*))
                      (contains? @origin [(:reg-types env) reg]))
             ;; the skill recorded at READ time, not the one bound now: it
             ;; is the read being attributed, and the two are only the same
             ;; if nothing about the binding has gone wrong. Storing it
             ;; removes the assumption instead of relying on it.
             (let [[src field nid skill form] (get @origin [(:reg-types env) reg])]
               (swap! sites conj {:skill skill :source src :field field
                                  :nid nid :target to* :form form}))))
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
      ;; Per-site nids, because the aggregate above cannot say WHICH read a
      ;; row is when a skill reads the same field twice -- and a count that
      ;; moves by less than the group size is exactly when that matters.
      (println "=== by site ===")
      (doseq [{:keys [skill source field nid target form]}
              (sort-by (juxt :skill :field (comp str :nid)) @sites)]
        (println (format "  %-22s %-16s %-24s -> %-8s %-8s %s"
                         (pr-str skill) (pr-str source) (pr-str field)
                         (pr-str target) (pr-str nid) form)))
      (println)

      ;; ZERO, and it is an invariant now rather than a ratchet: no field
      ;; read in shipped content reaches a :double/:long parameter carrying
      ;; :any. The next one that does fails this test, which is the point --
      ;; each such read compiles a :convert that throws on nil, so the check
      ;; is worth more at 0 than any backlog number it passed through.
      ;;
      ;; It started at 83. What the descent taught, worth keeping because
      ;; it decides how to attack the next class of these:
      ;;
      ;; The sites were never scattered. They clustered behind a handful of
      ;; untyped SOURCES, and typing one source retired a whole group:
      ;; ?context/resources alone was 33, the block selection 9, the beam
      ;; hit record 4. Chasing individual reads would have been the wrong
      ;; unit of work throughout.
      ;;
      ;; Twenty-two of the 83 never existed. This census keyed its
      ;; register-origin map by bare register, then by [skill register],
      ;; and both collide -- alloc-reg! numbers slots per PROGRAM while the
      ;; atom lives for a whole 50-skill assemble, and a document compiles
      ;; one program per entry point. It invented exactly the alarming kind
      ;; of row, claiming :owner-snapshot's :velocity (a {:x :y :z} map)
      ;; reached a :double six times. It cannot: that field is declared
      ;; :vec3. Reading the schema is what disproved it -- a typed field
      ;; makes a false claim about itself checkable.
      ;;
      ;; The last 8 were not type gaps at all, and none was fixed by adding
      ;; a type:
      ;;
      ;;   raycast! normalizes ten neutral fields onto whatever a loader
      ;;     bridge returned, and :distance was not among them, so the miss
      ;;     branch had none. It is computed there now, from the hit point
      ;;     and the ray origin, for hits and misses alike.
      ;;   the three mine-rays read :fortune-level and :tool-tier-capped?
      ;;     out of :presentation, a blob of sounds and colours, via a
      ;;     capability. They are per-variant gameplay constants, and are
      ;;     now literal inputs on the nodes that consume them.
      ;;   mark-teleport read :distance off session state because
      ;;     target/raycast-destination returned {:hit :destination} as a
      ;;     map literal -- an untyped register -- to carry a :hit half
      ;;     nothing read. The wrapper is gone and the state slot names its
      ;;     real type.
      ;;   vec-deviation's (:explosion-power projectile) had a working
      ;;     fallback that was unreachable: the guard choosing it was
      ;;     (math/gt power 0.0), whose :double params coerce, so the test
      ;;     threw before the select it guarded could run.
      ;;
      ;; The pattern in all four: an :any-typed read into a numeric
      ;; parameter is a symptom. The cause was an incomplete normalizer,
      ;; gameplay data in a presentation map, a wrapper carrying a dead
      ;; key, and a guard that could not be asked. Adding types would have
      ;; hidden every one of them.
      (is (= 0 (count @sites))
          (str "field reads reaching a numeric parameter changed. Each is a"
               " :convert that throws on nil. Type its source, or fix the"
               " producer -- do not add a default to hide it: "
               (pr-str (sort-by (juxt :skill :field) @sites)))))))
