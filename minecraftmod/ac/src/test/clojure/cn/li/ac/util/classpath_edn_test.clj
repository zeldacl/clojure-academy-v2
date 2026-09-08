(ns cn.li.ac.util.classpath-edn-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [cn.li.ac.util.classpath-edn :as classpath-edn]))

(deftest edn-resource-names-finds-skill-and-smoke-documents
  (let [names (set (classpath-edn/edn-resource-names
                    "ac/skills-v3"
                    {:sentinels ["arc-gen.edn" "catalog_smoke.edn"]}))]
    (is (contains? names "ac/skills-v3/arc-gen.edn"))
    (is (contains? names "ac/skills-v3/catalog_smoke.edn"))
    (is (<= 51 (count names)))))

(deftest edn-names-from-file-url-lists-siblings
  "Loom often only exposes a file URL for a single EDN entry; sibling walk
   must still discover the rest of the catalog directory."
  (let [url (io/resource "ac/skills-v3/arc-gen.edn")
        names (classpath-edn/edn-names-from-url "ac/skills-v3" url)]
    (is (some? url))
    (is (contains? names "ac/skills-v3/arc-gen.edn"))
    (is (contains? names "ac/skills-v3/railgun.edn"))
    (is (<= 50 (count names)))))

(deftest probe-candidates-resolves-existing-files
  (is (= ["ac/skills-v3/arc-gen.edn" "ac/skills-v3/catalog_smoke.edn"]
         (#'classpath-edn/probe-candidates
          "ac/skills-v3"
          ["arc-gen.edn" "catalog_smoke.edn" "nope.edn"]))))
