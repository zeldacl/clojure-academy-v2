(ns cn.li.combat.dependency-direction-test
  "Real-parsing replacement for the per-.clj-file half of build.gradle's
   verifyCombatDependencyDirection gate, which grepped each combat-core
   source file's whole text (no comment/docstring stripping at all, unlike
   the platform-src half of the same gate a few lines below it) for a
   forbidden namespace prefix. That flagged a DOCSTRING merely mentioning
   a forbidden namespace in prose -- e.g. source_runtime.clj's docstring
   explains that :ability/caster's output ports rename the existing AC
   caster-facade's keys, to justify why the mapping table looks the way
   it does, not because this namespace requires AC -- exactly the same bug
   class already fixed for node-core (dependency-direction-test) and
   vfx-core (dependency-direction-test) in earlier commits this session.
   This test reads each file's real ns form and checks the namespace/
   package symbols its own :require/:import clauses actually reference."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.ac" "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211"
   "cn.li.mc262" "cn.li.forge" "cn.li.fabric" "cn.li.neoforge" "cn.li.presentation"])

(defn- forbidden? [sym-or-str]
  (let [s (str sym-or-str)]
    (some #(or (= s %) (.startsWith s (str % "."))) forbidden-prefixes)))

(defn- clj-files [dir-path]
  (->> (io/file dir-path)
       file-seq
       (filter #(and (.isFile ^java.io.File %)
                     (or (.endsWith (.getName ^java.io.File %) ".clj")
                         (.endsWith (.getName ^java.io.File %) ".cljc"))))))

(defn- read-ns-form! [^java.io.File file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (read reader))))

(defn- require-entry-namespaces [entry]
  (cond
    (symbol? entry) [entry]
    (vector? entry) (if (symbol? (first entry)) [(first entry)] (mapcat require-entry-namespaces entry))
    :else nil))

(defn- import-entry-classes [entry]
  (cond
    (symbol? entry) [(str entry)]
    (vector? entry) (let [[pkg & classes] entry]
                      (mapv #(str pkg "." %) classes))
    :else nil))

(defn- referenced-namespaces [ns-form]
  (let [clauses (filter list? ns-form)]
    (concat
     (mapcat (fn [[tag & entries]] (when (= :require tag) (mapcat require-entry-namespaces entries))) clauses)
     (mapcat (fn [[tag & entries]] (when (= :import tag) (mapcat import-entry-classes entries))) clauses))))

(deftest combat-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (for [file (clj-files "src/main/clojure")
                         :let [ns-form (read-ns-form! file)]
                         referenced (referenced-namespaces ns-form)
                         :when (forbidden? referenced)]
                     [(.getPath ^java.io.File file) referenced])]
    (is (empty? violations)
        (str "combat-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (clj-files "src/main/clojure")) 8)))

