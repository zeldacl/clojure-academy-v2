(ns cn.li.ac.ability.editor.editor-corpus-test
  "Validate every production V3 asset through the structured reader used by
   the visual editor. No legacy wrapper or string DSL path is accepted."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.ability.editor.document :as editor-document]
            [cn.li.node.document :as node-document]))

(defn- edn-files [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile %) (.endsWith (.getName %) ".edn")))
       (map #(.getPath %)) sort))

(def ^:private skill-files (edn-files "src/main/resources/ac/skills-v3"))
(def ^:private vfx-files (edn-files "src/main/resources/ac/vfx-v3"))

(deftest v3-corpus-is-present-test
  (is (= 50 (count skill-files)))
  (is (= 36 (count vfx-files))))

(defn- check-file! [path]
  (let [raw (slurp path)
        parsed (binding [*read-eval* false] (edn/read-string raw))
        opened (editor-document/open-v3 raw)]
    (testing (str path " is a validated structured editor document")
      (node-document/validate-document! parsed)
      (is (true? (:v3? opened)))
      (is (= (:schema parsed) (get-in opened [:v3-document :schema])))
      (is (= (:id parsed) (get-in opened [:v3-document :id])))
      (is (map? (:form opened)))
      (is (seq (:phases (:form opened)))))))

(deftest every-skill-v3-file-opens-in-editor-test
  (doseq [path skill-files] (check-file! path)))

(deftest every-vfx-v3-file-opens-in-editor-test
  (doseq [path vfx-files] (check-file! path)))