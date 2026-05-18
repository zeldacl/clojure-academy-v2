(ns cn.li.test-support.auto-test-runner
  "Shared clojure.test auto-discovery runner for repository modules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-file?
  [^java.io.File file]
  (and (.isFile file)
       (str/ends-with? (.getName file) "_test.clj")))

(defn- file->namespace
  [base-dir base-ns ^java.io.File file]
  (let [base-path (.toPath ^java.io.File base-dir)
        file-path (.toPath file)
        relative (.relativize base-path file-path)
        segments (iterator-seq (.iterator relative))
        ns-parts (->> segments
                      (map str)
                      (map #(str/replace % #"\.clj$" ""))
                      (map #(str/replace % "_" "-")))]
    (symbol (str/join "." (cons base-ns ns-parts)))))

(defn- discover-test-namespaces
  [{:keys [root-segments base-ns]}]
  (let [root-dir (apply io/file root-segments)]
    (when-not (.isDirectory root-dir)
      (throw (ex-info "Test root directory not found"
                      {:root (.getPath root-dir)})))
    (->> (file-seq root-dir)
         (filter test-file?)
         (map #(file->namespace root-dir base-ns %))
         (sort-by str)
         vec)))

(defn- requested-namespaces
  [property-name]
  (when-let [raw (some-> property-name System/getProperty)]
    (->> (str/split raw #",")
         (map str/trim)
         (remove str/blank?)
         (map symbol)
         set)))

(defn- select-namespaces
  [namespaces only]
  (if (seq only)
    (let [available (set namespaces)
          selected (filterv only namespaces)
          missing (seq (remove available only))]
      (when missing
        (throw (ex-info "Requested test namespaces were not discovered"
                        {:missing (vec missing)})))
      selected)
    namespaces))

(defn- exit-code
  [{:keys [fail error]}]
  (if (or (pos? (long fail))
          (pos? (long error)))
    1
    0))

(defn run-tests!
  "Discover, require, and run clojure.test namespaces.

  Options:
  - `:root-segments`: path segments, relative to the module working directory,
    that point at the module's test namespace root.
  - `:base-ns`: namespace prefix represented by `:root-segments`.
  - `:only-property`: optional system property with a comma-separated list of
    fully-qualified test namespaces to run."
  [{:keys [only-property] :as opts}]
  (let [namespaces (discover-test-namespaces opts)
        only (requested-namespaces only-property)
        selected (select-namespaces namespaces only)]
    (when-not (seq selected)
      (throw (ex-info "No test namespaces selected" opts)))
    (println "Discovered" (count namespaces) "test namespaces; running" (count selected))
    (doseq [ns-sym selected]
      (require ns-sym))
    (let [result (apply test/run-tests selected)
          code (exit-code result)]
      (shutdown-agents)
      (when-not (zero? code)
        (System/exit code))
      result)))