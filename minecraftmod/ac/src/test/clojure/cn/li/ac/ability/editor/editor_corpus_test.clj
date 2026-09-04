(ns cn.li.ac.ability.editor.editor-corpus-test
  "The node editor's language-level round-trip guarantee, proven against
   the REAL corpus (39 ac/skills/*.edn + 36 ac/vfx/fx/*.edn), not a
   hand-picked fixture set -- ability-runtime's own graph_test.clj/
   document_test.clj can only prove the mechanism works on synthetic
   examples (ability-runtime has no dependency on ac's resources, wrong
   direction); only ac can load the real files this editor will actually
   open. Covers two independent guarantees together:
     1. cn.li.ability.editor.document/splice-program: saving without
        touching anything outside the :program/:scene string is
        byte-identical when the DSL text itself is unchanged.
     2. cn.li.ability.editor.graph/form->graph->form: every real
        statement shape in production content (including `if`, and
        control flow nested inside `when`/`each`/`if`) round-trips to an
        identical form.
   A regression in either here means the editor would corrupt or refuse
   to open real content -- this is the test that actually matters, the
   synthetic-fixture tests in ability-runtime exist to localize a
   failure faster, not to replace this one."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cn.li.ability.editor.document :as document]
            [cn.li.ability.editor.graph :as graph]))

(defn- edn-files [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile %) (.endsWith (.getName %) ".edn") (not= "manifest.edn" (.getName %))))
       (map #(.getPath %))
       sort))

(def ^:private skill-files (edn-files "src/main/resources/ac/skills"))
(def ^:private vfx-files (edn-files "src/main/resources/ac/vfx/fx"))

(deftest corpus-is-non-empty-sanity-test
  ;; Guards against a silently-broken path (e.g. a working-directory
  ;; change) making every other test in this file vacuously pass.
  (is (= 39 (count skill-files)))
  (is (= 36 (count vfx-files))))

(defn- entries-of [form]
  (cond
    (contains? form :phases) (:phases form)
    (contains? form :do) {:default (:do form)}
    :else (throw (ex-info "document has neither :do nor :phases" {:form form}))))

(defn- check-file! [path field]
  (let [raw (slurp path)
        doc (document/open raw field)]
    (testing (str path " splice-program round-trip with unchanged DSL text is byte-identical")
      (let [dsl-text (subs raw (first (document/string-value-span raw field))
                            (second (document/string-value-span raw field)))
            spliced (document/splice-program raw field (#'document/unescape-edn-string dsl-text))]
        (is (= raw spliced))))
    (doseq [[phase stmts] (entries-of (:form doc))]
      (testing (str path " phase " phase " form->graph->form round-trip")
        (let [g (graph/form->graph stmts)
              back (graph/graph->form g)]
          (is (= stmts back)))))))

(deftest every-skill-file-round-trips-test
  (doseq [f skill-files] (check-file! f :program)))

(deftest every-vfx-file-round-trips-test
  (doseq [f vfx-files] (check-file! f :scene)))
