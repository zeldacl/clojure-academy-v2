(ns cn.li.presentation.core.dep-check
  "Local copy of cn.li.node.dep-check (node-core/src/test/clojure/cn/li/node/
   dep_check.clj), for presentation-core's own dependency_direction_test.clj.

   Not shared via node-core's test tree on purpose: combat-core/vfx-core pull
   in the whole node-core/src/test/clojure directory because they already
   depend on node-core's main classes for real, so the rest of that
   directory's test files (which need those classes to compile) come along
   for free. presentation-core deliberately has ZERO node-core dependency --
   this checker exists to prove exactly that -- so reaching into node-core's
   classpath just to satisfy unrelated pulled-in test files would undermine
   the invariant being tested. If node-core's copy changes, mirror the change
   here; the logic is stable utility code, not something expected to drift.

   Reads every top-level form, not just the ns form: some source files in
   this repo put a real dependency in a top-level (require ...)/(import ...)
   form after the ns form, invisible to a naive ns-form-only reader."
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn- read-all-forms
  [^java.io.File file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [acc []]
        (let [form (read {:eof ::eof} reader)]
          (if (= ::eof form) acc (recur (conj acc form))))))))

(defn- unquote-arg
  [arg]
  (if (and (sequential? arg) (= 'quote (first arg))) (second arg) arg))

(defn- require-entry-namespaces
  [entry]
  (cond
    (symbol? entry) [entry]
    (vector? entry) (if (symbol? (first entry)) [(first entry)] (mapcat require-entry-namespaces entry))
    :else nil))

(defn- import-entry-classes
  [entry]
  (cond
    (symbol? entry) [(str entry)]
    (vector? entry) (let [[pkg & classes] entry]
                      (mapv #(str pkg "." %) classes))
    :else nil))

(defn- ns-form-namespaces
  [ns-form]
  (let [clauses (filter list? ns-form)]
    (concat
     (mapcat (fn [[tag & entries]] (when (= :require tag) (mapcat require-entry-namespaces entries))) clauses)
     (mapcat (fn [[tag & entries]] (when (= :import tag) (mapcat import-entry-classes entries))) clauses))))

(defn- top-level-form-namespaces
  [[head & args]]
  (case head
    (require use) (mapcat require-entry-namespaces (map unquote-arg args))
    import (mapcat import-entry-classes (map unquote-arg args))
    nil))

(defn referenced-namespaces
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
  [source-root forbidden-prefixes]
  (for [file (clj-files source-root)
        referenced (referenced-namespaces file)
        :when (forbidden? forbidden-prefixes referenced)]
    {:file (.getPath ^java.io.File file) :ns referenced}))
