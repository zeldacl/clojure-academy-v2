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
      ;; group at once.
      ;;
      ;; 28 -> 19 finished the :hardness cluster, and took three steps to
      ;; find because the reads were not in any skill. combat-core's
      ;; break-budget LIBRARY function does (:hardness candidate) in an
      ;; `each` body; it is inlined into railgun and meltdowner, which is
      ;; why their EDN does not contain the word. Typing the list retires
      ;; all of them at once, since compile-each already binds the item
      ;; with the collection's element type -- what was missing was a
      ;; collection with one.
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
      ;; 19 -> 9 -> 8 typed the last three records whose producers DO guarantee
      ;; their numeric fields: :entity-ref (project-entity, every numeric
      ;; field written (double (or ... d))), :beam-hit (beam-trace!'s hit
      ;; record, which had been mistaken for an :entity-ref), and the
      ;; block selection before them.
      ;;
      ;; The 8 that remain are NOT untyped for lack of effort. Every one is
      ;; a read of a field whose producer can legitimately omit it, so
      ;; declaring a type would be a false claim and defaulting the value
      ;; host-side would invent data to hide a defect. They are left
      ;; visible on purpose, in four groups:
      ;;
      ;;   :hit-result :distance x4 (penetrate-teleport). raycast!'s miss
      ;;     branch returns {:hit-type :miss :hit? false :world-id :owner}
      ;;     with no :distance at all, and on a hit the key comes straight
      ;;     from the loader bridge -- platform.clj's own block-vs-entity
      ;;     comparison defends with (or (:distance x) INFINITY), which is
      ;;     the producer admitting it does not guarantee the key.
      ;;   :context/ability-runtime :fortune-level x3 (the three mine-rays).
      ;;     That capability is AC's (:presentation registration) -- open
      ;;     registration metadata whose keys are content-defined. Note
      ;;     :block/break's own :fortune-level param is (opt :long 0), so
      ;;     content is passing an explicitly-read value where the node's
      ;;     default would already have been correct.
      ;;   a state read x1 (mark-teleport saves a destination to session
      ;;     state and reads :distance back out). Persisted session state
      ;;     has no static shape by construction.
      ;; A fifth group was here and is now FIXED rather than documented:
      ;; vec-deviation's (:explosion-power projectile). The host builds that
      ;; field as (when (instance? LargeFireball entity) ...), so nil is the
      ;; normal case for every other projectile.
      ;;
      ;; The skill's fallback was not missing -- (math/select is-fireball
      ;; power radius) was wired, with :fireball-explosion-radius already
      ;; declared and mapped to main's own config key, and math/select's
      ;; :any params let nil pass through it harmlessly. What defeated it
      ;; was the GUARD: the condition was (math/gt power 0.0), whose
      ;; :double params coerce, so the test that would have chosen the
      ;; fallback threw before the select could run. The fallback was
      ;; unreachable, not absent.
      ;;
      ;; It now reads the skill's own :is-large-fireball? local -- the
      ;; large-fireball-ids membership test it already computes -- which is
      ;; both nil-safe and closer to main than the comparison was: main
      ;; used `or`, falling back only on nil, while (> power 0.0) also fell
      ;; back for a fireball whose power really is zero.
      ;;
      ;; Each is the same explicit decision :resource-pool went through: say
      ;; whether the producing expression can return nil, then either
      ;; declare it and whitelist it with the reason, or fix the producer.
      (is (= 8 (count @sites))
          (str "field reads reaching a numeric parameter changed. Each is a"
               " potential :convert throw; justify a new one or remove it: "
               (pr-str (sort-by (juxt :skill :field) @sites)))))))
