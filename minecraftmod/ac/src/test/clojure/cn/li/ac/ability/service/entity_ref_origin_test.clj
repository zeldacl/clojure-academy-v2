(ns cn.li.ac.ability.service.entity-ref-origin-test
  "Is :entity-ref one type or two?

   It tags both an opaque handle -- what :entity/spawn hands back -- and the
   ELEMENTS of :target/entities, whose producer (platform/project-entity)
   builds a full record: {:id :type :position :width :height :eye-height
   :age-ms :motion-progress :owner-id :velocity :difficulty}. Content reads
   fields off :entity-ref-typed values 45 times, the largest remaining
   typed source.

   If any of those reads is against a genuine handle, it returns nil at
   runtime -- the same defect already confirmed for (:position x) on an
   :entity-snapshot, whose producer has no :position key. That pattern (a
   tag coarser than the records it covers) has now produced one live bug
   and two near-misses, so this asks the compiler rather than guessing.

   Method: record the source register of every field read whose source type
   is :entity-ref, then walk the IR back through :copy/:convert and through
   :collection/nth and :collection/first -- how an `each` item and a list
   head reach a local -- until the instruction that actually produced the
   value is reached. A :query names its vocab node, which is the answer."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.skills-catalog-v4 :as skills-catalog]
            [cn.li.node.types :as types]))

(def ^:private compile-ns (do (require 'cn.li.node.compile) 'cn.li.node.compile))
(def ^:private compile-field-access-var (ns-resolve compile-ns 'compile-field-access))

(defn- writer-index
  "All instructions in every block of one compile env, indexed by :dst."
  [registry]
  (into {}
        (for [[_ instrs-atom] registry
              instr @instrs-atom
              :when (:dst instr)]
          [(:dst instr) instr])))

(defn- origin
  "Follow a register back to the instruction that produced its value."
  [writers reg seen]
  (let [instr (get writers reg)]
    (cond
      (nil? instr) {:kind :unwritten}
      (contains? seen reg) {:kind :cycle}
      (contains? #{:copy :convert} (:op instr))
      (recur writers (:src instr) (conj seen reg))
      ;; An `each` item is :collection/nth over the collection; a list head
      ;; is :collection/first. Both forward their argument's element, so the
      ;; interesting producer is one level further back.
      (and (= :pure (:op instr))
           (contains? #{:collection/nth :collection/first} (:fn instr)))
      (recur writers (first (:args instr)) (conj seen reg))
      (= :query (:op instr)) {:kind :query :node (:node instr)}
      (= :pure (:op instr)) {:kind :pure :fn (:fn instr)}
      :else {:kind (:op instr)})))

(deftest entity-ref-field-reads-trace-back-to-their-producer
  (let [pending (atom [])
        orig @compile-field-access-var]
    (with-redefs-fn
      {compile-field-access-var
       (fn [env locals block-id depth form k sub-form]
         (let [result (orig env locals block-id depth form k sub-form)
               instrs (some-> (get @(:block-registry env) (:block-id result)) deref)
               get-instr (last (filter #(= :get (:op %)) instrs))
               src-type (get @(:reg-types env) (:src get-instr))]
           (when (and get-instr (= :entity-ref (types/canonical-type src-type)))
             ;; Resolve later: this block is still being appended to.
             (swap! pending conj {:registry (:block-registry env)
                                  :src (:src get-instr)
                                  :field k}))
           result))}
      #(skills-catalog/assemble {:mode :collect}))

    (let [rows (for [{:keys [registry src field]} @pending
                     :let [o (origin (writer-index @registry) src #{})]]
                 {:field field :origin o})
          by-origin (frequencies (map (comp #(or (:node %) (:fn %) (:kind %)) :origin) rows))
          by-pair (frequencies (map (fn [r] [(or (:node (:origin r))
                                                 (:fn (:origin r))
                                                 (:kind (:origin r)))
                                             (:field r)])
                                    rows))]
      (println)
      (println "=== :entity-ref field reads, by the node that produced the value ===")
      (println "total:" (count rows))
      (println)
      (doseq [[o n] (sort-by (comp - val) by-origin)]
        (println (format "  %5d  %s" n (pr-str o))))
      (println)
      (println "producer -> field:")
      (doseq [[[o f] n] (sort-by (comp - val) by-pair)]
        (println (format "  %5d  %-26s %s" n (pr-str o) (pr-str f))))
      (println)

      (is (seq rows) "expected :entity-ref field reads to exist")

      ;; The answer, as an assertion rather than a printout: :entity-ref is
      ;; a ROLE, not a record. Two producers, and their key sets do not
      ;; overlap at all --
      ;;
      ;;   :target/entities -> platform/project-entity's record, keyed :id
      ;;   :entity/spawn    -> {:status :entity-id}, keyed :entity-id
      ;;
      ;; -- so the intersection is EMPTY and no field schema for
      ;; :entity-ref can exist. Those 45 reads are not addressable by the
      ;; field-schema work, which is worth knowing before spending on it.
      ;; What actually unifies them is that every consumer resolves an id
      ;; with (or (:id e) (:uuid e) (:entity-id e)) -- see
      ;; platform/discard-entity! -- exactly as :vec3 means "a position
      ;; platform/point can read" rather than one encoding.
      ;;
      ;; A third producer would break that reasoning, so pin the set.
      (is (= #{:target/entities :entity/spawn}
             (set (keep (comp :node :origin) rows)))
          (str "a new :entity-ref producer appeared. Check whether its shape "
               "shares any field with the other two before assuming the role "
               "tag still holds: " (pr-str by-origin)))
      (is (= #{} (set (keep (comp :fn :origin) rows)))
          "an :entity-ref now comes out of a pure op, which the role story does not cover"))))
