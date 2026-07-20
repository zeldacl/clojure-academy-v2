(ns cn.li.test-support.auto-test-runner
  "Shared clojure.test auto-discovery runner for repository modules."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [cn.li.mcmod.framework :as fw]))

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

(defn- elapsed-ms
  [started-at-ns]
  (long (/ (- (System/nanoTime) started-at-ns) 1000000)))

(defn- merge-test-result
  [acc result]
  (merge-with + acc (select-keys result [:test :pass :fail :error])))

(defn- print-summary!
  [{:keys [test pass fail error]}]
  (println)
  (println "Ran" test "tests containing" (+ pass fail error) "assertions.")
  (println fail "failures," error "errors."))

(defn- print-slowest-namespaces!
  [timings]
  (when (seq timings)
    (println "Slowest test namespaces:")
    (doseq [{:keys [ns elapsed-ms]} (take 10 (sort-by (comp - :elapsed-ms) timings))]
      (println " " ns (str elapsed-ms "ms")))))

(defn- with-fresh-framework*
  "Run `thunk` with a fresh Framework atom var-rooted in, restoring the
   previous one after. Framework-backed state (config registry, hooks,
   managed screens, context dispatcher, ...) reads through fw/framework;
   without one, writes land in throwaway atoms and state silently vanishes.
   Installing one per test NAMESPACE gives every suite a working, isolated
   framework by default — namespace fixtures may still install their own."
  [thunk]
  (let [prev fw/framework]
    (try
      (when-let [fw-inst (fw/create-framework)]
        (alter-var-root #'fw/framework (constantly fw-inst)))
      (thunk)
      (finally
        (alter-var-root #'fw/framework (constantly prev))))))

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
    (let [require-start (System/nanoTime)]
      (doseq [ns-sym selected]
        (require ns-sym))
      (println "Required" (count selected) "test namespaces in" (str (elapsed-ms require-start) "ms")))
    (let [timings (mapv (fn [ns-sym]
                          (let [started-at (System/nanoTime)
                                result (with-fresh-framework* #(test/test-ns ns-sym))
                                elapsed (elapsed-ms started-at)]
                            (println "Finished" ns-sym "in" (str elapsed "ms"))
                            {:ns ns-sym
                             :elapsed-ms elapsed
                             :result result}))
                        selected)
          ;; Fresh frameworks per namespace accumulate GC roots; 259
          ;; namespaces without a collection cycle will OOM.
          _ (System/gc)
          result (reduce merge-test-result
                         {:test 0 :pass 0 :fail 0 :error 0}
                         (map #(get % :result) timings))
          code (exit-code result)]
      (print-summary! result)
      (print-slowest-namespaces! timings)
      (shutdown-agents)
      (when-not (zero? code)
        (System/exit code))
      result)))