(ns cn.li.node.dep-check
  "Shared dependency-direction checker for node-core, combat-core and
   vfx-core's per-module dependency_direction_test.clj files.

   Two bugs in the pre-existing per-module copies of this logic, both fixed
   here:

   1. Each copy read only a file's FIRST top-level form (its `ns` form) and
      checked the namespace/package symbols referenced by that form's
      :require/:import clauses. Six main-source files put their actual
      dependency in a SECOND, top-level (require ...) or (import ...) form
      AFTER the ns form -- combat-core's final_compiler.clj, final_damage.clj,
      final_engine.clj, and vfx-core's effect_schema.clj, network.clj,
      replication.clj. Those six files were therefore completely unchecked:
      the first-form-only test could not see the real form, and the Groovy
      grep gate this test replaced had already been retired in favor of it.
      This checker reads every top-level form to EOF.

   2. A file writing `(require '[a.b.c :as x])` at the top level (rather than
      inside an `ns` form's :require clause) has its spec wrapped in `(quote
      ...)` once read -- `(require '[a.b.c :as x])` reads as
      `(require (quote [a.b.c :as x]))`. All six files above use exactly this
      quoted-vector top-level form. Without unwrapping the quote, a naive
      extension of the ns-form-only reader would still miss every one of
      them. `unquote-arg` below undoes this.

   Each consuming module's test just supplies its own source root and its own
   forbidden-prefix list; this namespace holds no state and does no I/O
   beyond reading the files it's given."
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn- read-all-forms
  "Every top-level form in `file`, to EOF -- not just the first (the `ns`
   form). *read-eval* is bound false as a matter of course when reading
   source we didn't author as trusted-executable."
  [^java.io.File file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [acc []]
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(defn- unquote-arg
  "A top-level (require '[a.b.c :as x]) reads as (require (quote [a.b.c :as
   x])) -- unwrap the quote so the spec underneath is visible to the same
   require-entry-namespaces logic the ns-form :require clause uses."
  [arg]
  (if (and (sequential? arg) (= 'quote (first arg))) (second arg) arg))

(defn- require-entry-namespaces
  "One :require entry -> the namespace symbols it references: a plain
   symbol, or the head of a [ns & opts] vector -- including a
   :require-macros-style nested vector-of-vectors, which this codebase does
   not use but costs nothing to handle."
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

(defn- ns-form-namespaces
  "Namespaces/packages referenced by an (ns ...) form's own :require/:import
   clauses."
  [ns-form]
  (let [clauses (filter list? ns-form)]
    (concat
     (mapcat (fn [[tag & entries]] (when (= :require tag) (mapcat require-entry-namespaces entries))) clauses)
     (mapcat (fn [[tag & entries]] (when (= :import tag) (mapcat import-entry-classes entries))) clauses))))

(defn- top-level-form-namespaces
  "Namespaces/packages referenced by a top-level (require ...), (import ...)
   or (use ...) form -- the second-form-and-later escape hatch six files in
   this repo actually use."
  [[head & args]]
  (case head
    (require use) (mapcat require-entry-namespaces (map unquote-arg args))
    import (mapcat import-entry-classes (map unquote-arg args))
    nil))

(defn referenced-namespaces
  "Every namespace/package `file` depends on: from its `ns` form's
   :require/:import clauses, AND from every top-level require/import/use
   form elsewhere in the file."
  [file]
  (let [forms (read-all-forms file)]
    (into #{}
          (map str)
          (mapcat (fn [form]
                    (cond
                      (and (list? form) (= 'ns (first form))) (ns-form-namespaces form)
                      (list? form) (top-level-form-namespaces form)
                      :else nil))
                  forms))))

(defn- forbidden? [forbidden-prefixes namespace-str]
  (some #(or (= namespace-str %) (.startsWith ^String namespace-str (str % ".")))
        forbidden-prefixes))

(defn clj-files [dir-path]
  (->> (io/file dir-path)
       file-seq
       (filter #(and (.isFile ^java.io.File %)
                     (or (.endsWith (.getName ^java.io.File %) ".clj")
                         (.endsWith (.getName ^java.io.File %) ".cljc"))))))

(defn violations
  "[{:file f :ns n}] for every namespace/package `source-root` depends on
   (recursively, across every .clj/.cljc file) that matches a forbidden
   prefix."
  [source-root forbidden-prefixes]
  (for [file (clj-files source-root)
        referenced (referenced-namespaces file)
        :when (forbidden? forbidden-prefixes referenced)]
    {:file (.getPath ^java.io.File file) :ns referenced}))
