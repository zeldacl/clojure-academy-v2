(ns cn.li.node.dependency-direction-test
  "Real-parsing replacement for build.gradle's verifyNodeCoreDependencyDirection
   gate's per-file scan, which grepped each node-core source file's WHOLE
   TEXT (docstrings included) for forbidden namespace prefixes. That is too
   broad: a docstring explaining, in prose, what a real caller supplies
   (e.g. \"cn.li.mcmod.runtime.safe-edn/read-resource!\", exactly the kind
   of sentence this module's own docstrings correctly write to explain why
   THIS namespace does not depend on it) trips the same regex as an actual
   (:require [cn.li.mcmod...]) form would. This test instead reads only
   each file's ns form and checks the namespace/package symbols its own
   :require/:import clauses actually reference."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.mcmod" "cn.li.ac" "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201"
   "cn.li.mc1211" "cn.li.mc262" "cn.li.forge" "cn.li.fabric" "cn.li.neoforge"
   "cn.li.presentation" "cn.li.combat" "cn.li.vfx"])

(defn- forbidden? [sym-or-str]
  (let [s (str sym-or-str)]
    (some #(or (= s %) (.startsWith s (str % "."))) forbidden-prefixes)))

(defn- clj-files [dir-path]
  (->> (io/file dir-path)
       file-seq
       (filter #(and (.isFile ^java.io.File %)
                     (or (.endsWith (.getName ^java.io.File %) ".clj")
                         (.endsWith (.getName ^java.io.File %) ".cljc"))))))

(defn- read-ns-form!
  "The file's first top-level form -- every Clojure source file in this
   codebase starts with (ns ...); *read-eval* is bound false as a matter
   of course when reading source we didn't author as trusted-executable."
  [^java.io.File file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (read reader))))

(defn- require-entry-namespaces
  "One :require entry -> the namespace symbols it references: a plain
   symbol, or the head of a [ns & opts] vector -- including a
   :require-macros-style nested vector-of-vectors, which this codebase
   does not use but costs nothing to handle."
  [entry]
  (cond
    (symbol? entry) [entry]
    (vector? entry) (if (symbol? (first entry)) [(first entry)] (mapcat require-entry-namespaces entry))
    :else nil))

(defn- import-entry-classes
  "One :import entry -> fully-qualified class name(s): a plain symbol
   (already fully qualified), or [package Class1 Class2 ...]."
  [entry]
  (cond
    (symbol? entry) [(str entry)]
    (vector? entry) (let [[pkg & classes] entry]
                      (mapv #(str pkg "." %) classes))
    :else nil))

(defn- referenced-namespaces
  "Every namespace/package this ns form's :require and :import clauses
   reference, as strings."
  [ns-form]
  (let [clauses (filter list? ns-form)]
    (concat
     (mapcat (fn [[tag & entries]] (when (= :require tag) (mapcat require-entry-namespaces entries))) clauses)
     (mapcat (fn [[tag & entries]] (when (= :import tag) (mapcat import-entry-classes entries))) clauses))))

(deftest node-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (for [file (clj-files "src/main/clojure")
                         :let [ns-form (read-ns-form! file)]
                         referenced (referenced-namespaces ns-form)
                         :when (forbidden? referenced)]
                     [(.getPath ^java.io.File file) referenced])]
    (is (empty? violations)
        (str "node-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (clj-files "src/main/clojure")) 8)))

