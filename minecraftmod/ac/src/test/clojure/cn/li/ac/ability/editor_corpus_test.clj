(ns cn.li.ac.ability.editor-corpus-test
  "The editor's form<->graph transform, round-tripped over every shipped
   skill.

   cn.li.ability.editor.graph's own docstring cites this namespace as what
   proves its coverage claims -- that it handles let/when/if/each/finish/
   state!/set!/event!/vfx! and arbitrarily deep expression trees over the
   real corpus rather than over hand-picked fixtures. The citation was
   accurate about what was needed and wrong about what existed: no such
   namespace was here. This is it.

   It lives in ac rather than next to graph.clj because ability-runtime
   cannot see ac's resources, and the whole point is to run against real
   content instead of fixtures.

   The property is exact equality, not equivalence: graph->form is a
   structural reconstruction of the surface AST, so anything other than
   the identical statement vector back is a defect.

   Node ids are checked separately and as CONTAINMENT, not equality, and
   the asymmetry is the transform's documented behaviour rather than a
   tolerance: a stamped form keeps its id, an unstamped one is allocated a
   fresh one (graph.clj's nid-of!), so a rebuilt phase legitimately
   carries ids the original did not. What would be a defect is the other
   direction -- an id present in the file and absent afterwards means the
   editor cannot save without breaking whatever pointed at it. = ignores
   metadata entirely, so without this the round-trip could drop every
   stamp and still pass."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [cn.li.node.api :as node-api]
            [cn.li.ability.editor.graph :as graph]
            [cn.li.ability.editor.document :as editor-doc]
            [cn.li.ac.ability.skills-catalog-v4 :as catalog]))

(defn- skill-docs []
  (into (sorted-map)
        (map (fn [{:keys [id document]}] [id document]))
        (:skills (catalog/assemble {:mode :collect}))))

(defn- nids [x]
  (let [acc (atom [])]
    (walk/postwalk (fn [f] (when-let [n (:nid (meta f))] (swap! acc conj n)) f) x)
    (sort @acc)))

(deftest every-shipped-phase-survives-the-editor-round-trip-test
  (let [results (for [[id doc] (skill-docs)
                      [phase stmts] (sort-by key (:phases doc))]
                  (let [back (graph/graph->form (graph/form->graph stmts))]
                    {:id id :phase phase
                     :equal? (= (vec stmts) back)
                     :lost (vec (remove (set (nids back)) (nids stmts)))
                     :fresh (- (count (nids back)) (count (nids stmts)))
                     :stmt-count (count stmts)}))
        broken (remove #(and (:equal? %) (empty? (:lost %))) results)]
    (println)
    (println "=== editor form<->graph over the shipped corpus ===")
    (println "phases:" (count results)
             "| statements:" (reduce + (map :stmt-count results))
             "| ids freshly allocated to unstamped forms:"
             (reduce + (map :fresh results)))
    (doseq [r (take 12 broken)]
      (println (format "  MISMATCH %-28s %-10s form=%s lost=%s"
                       (pr-str (:id r)) (pr-str (:phase r))
                       (:equal? r) (pr-str (:lost r)))))
    (println)
    (is (seq results) "no phases were loaded -- the assertion below would be vacuous")
    (is (= [] (vec broken))
        "a shipped phase does not survive form->graph->form, so the editor
         cannot open and re-save it without changing it")))

(deftest the-corpus-actually-exercises-every-statement-kind-test
  ;; The round-trip above is only as good as what it covers. graph.clj
  ;; claims support for a specific list of statement kinds; this asserts
  ;; the corpus really contains them, so a pass means something.
  (let [kinds (->> (for [[_ doc] (skill-docs)
                         [_ stmts] (:phases doc)
                         node (vals (:nodes (graph/form->graph stmts)))]
                     (:stmt node))
                   (remove nil?)
                   set)]
    (println "statement kinds exercised:" (pr-str (sort kinds)))
    ;; :when is deliberately not required. The grammar has it and graph.clj
    ;; supports it, but nothing in shipped content uses it -- lowering emits
    ;; a two-armed `if` even for a one-armed branch -- so demanding it here
    ;; would assert about the compiler's output style, not about coverage.
    (doseq [k [:let :if :each :call :state! :vfx! :finish :event! :set!]]
      (is (contains? kinds k)
          (str "the corpus no longer exercises " k
               " -- the round-trip proof above stopped covering it")))))

(deftest a-skill-file-on-disk-is-what-the-editor-reads-test
  ;; The catalog hands out parsed documents; the editor opens the FILE.
  ;; Assert the two agree, so the round-trip above is about shipped bytes
  ;; rather than about something the catalog reshaped on the way through.
  (let [r "ac/skills-v4/vec-deviation.edn"
        from-disk (node-api/read-surface-document (slurp (io/resource r)))
        from-catalog (get (skill-docs) :vec-deviation)]
    (is (= from-catalog from-disk))
    (is (= (nids from-catalog) (nids from-disk))
        "the ^{:nid} stamps must survive the catalog's own read")))

(deftest opening-and-saving-a-skill-unchanged-leaves-it-unchanged-test
  ;; The editor's actual contract, and the one the corpus round-trip above
  ;; does not cover: open a shipped file, save it without editing, and the
  ;; result must be the same skill.
  ;;
  ;; Asserted at two strengths, because they fail for different reasons.
  ;; DOCUMENT equality is correctness -- anything else means saving mutates
  ;; content. TEXT equality is reviewability: if a no-op save rewrites the
  ;; bytes, every real edit arrives as a whole-file diff and the move to a
  ;; readable format bought nothing.
  (let [paths (for [r ["ac/skills-v4/vec-deviation.edn"
                       "ac/skills-v4/mark-teleport.edn"
                       "ac/skills-v4/mag-manip.edn"
                       "ac/skills-v4/railgun.edn"]]
                [r (slurp (io/resource r))])
        results (for [[r text] paths]
                  (let [saved (:file-text (editor-doc/save (editor-doc/open text) nil))]
                    {:resource r
                     :doc-equal? (= (node-api/read-surface-document text)
                                    (node-api/read-surface-document saved))
                     :nids-equal? (= (nids (node-api/read-surface-document text))
                                     (nids (node-api/read-surface-document saved)))
                     :text-equal? (= text saved)}))]
    (doseq [{:keys [resource doc-equal? nids-equal? text-equal?]} results]
      (println (format "  %-38s doc=%s nids=%s text=%s"
                       resource doc-equal? nids-equal? text-equal?)))
    (is (every? :doc-equal? results)
        "a no-op save changed the skill")
    (is (every? :nids-equal? results)
        "a no-op save lost node ids -- diagnostics and jump-to-node point at them")
    (is (every? :text-equal? results)
        "a no-op save rewrote the file, so every edit will diff as a whole file")))
