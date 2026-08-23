(ns cn.li.vfx.dependency-direction-test
  "Real-parsing replacement for the per-.clj-file half of build.gradle's
   verifyVfxDependencyDirection gate, which grepped each vfx-core source
   file's whole text (after stripping ;-comments only) for a forbidden
   namespace prefix. That still flagged a DOCSTRING (a string literal, not
   a ;-comment, so the existing comment-stripping workaround does not
   touch it) merely mentioning a forbidden namespace in prose -- e.g.
   composite_loader.clj's docstring explains 'mirrors combat-core's
   pre-existing v2 pattern (recipe.clj's load-composites!)' to justify why
   THIS namespace does not depend on it, and ops.clj's docstring explains
   why constructing cn.li.mcmod.math.V3 directly does not cross the
   platform/AC boundary this gate polices -- both tripped the same regex
   an actual (:require [cn.li.combat...]) would. This test instead reads
   each file's real ns form and checks the namespace/package symbols its
   own :require/:import clauses actually reference."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]))

(def ^:private forbidden-prefixes
  ["net.minecraft" "net.minecraftforge" "net.fabricmc" "net.neoforged"
   "cn.li.platform" "cn.li.mcbase" "cn.li.mc1201" "cn.li.mc1211" "cn.li.mc262"
   "cn.li.ac" "cn.li.presentation" "cn.li.combat"])

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

(deftest vfx-core-source-has-no-forbidden-namespace-dependency-test
  (let [violations (for [file (clj-files "src/main/clojure")
                         :let [ns-form (read-ns-form! file)]
                         referenced (referenced-namespaces ns-form)
                         :when (forbidden? referenced)]
                     [(.getPath ^java.io.File file) referenced])]
    (is (empty? violations)
        (str "vfx-core files with a forbidden namespace reference: " (vec violations)))))

(deftest sanity-scanned-more-than-a-handful-of-files-test
  (is (>= (count (clj-files "src/main/clojure")) 5)))
