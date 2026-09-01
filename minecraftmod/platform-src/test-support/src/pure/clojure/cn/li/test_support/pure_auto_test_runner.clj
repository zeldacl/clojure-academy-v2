(ns cn.li.test-support.pure-auto-test-runner
  "Dependency-free clojure.test auto-discovery runner, shared by node-core,
   presentation-core and presentation-compiler.

   This is a leaner sibling of cn.li.test-support.auto-test-runner (ac's and
   mcmod's shared runner): same discovery/require/run shape, but with NO
   dependency on cn.li.mcmod.framework's per-namespace fixture. That fixture
   exists to isolate AC's global registries between test namespaces; none of
   the three consumers here have that kind of global state, and node-core in
   particular must never depend on mcmod at all, even in test scope (see
   cn.li.node.dependency-direction-test) -- reusing the mcmod-coupled runner
   there would be a straightforward layering violation, not a convenience.

   Lives in its own directory (platform-src/test-support/src/pure/clojure),
   separate from cn.li.test-support.auto-test-runner and runtime-facades
   (platform-src/test-support/src/main/clojure), so that a consumer adding
   this directory as a test srcDir never also pulls in the mcmod- and
   Minecraft-coupled files those contain."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]))

(defn- test-file? [^java.io.File file]
  (and (.isFile file) (str/ends-with? (.getName file) "_test.clj")))

(defn- file->namespace [^java.io.File base-dir base-ns ^java.io.File file]
  (let [relative (.relativize (.toPath base-dir) (.toPath file))
        segments (map str (iterator-seq (.iterator relative)))
        ns-parts (map #(-> % (str/replace #"\.clj$" "") (str/replace "_" "-")) segments)]
    (symbol (str/join "." (cons base-ns ns-parts)))))

(defn discover-test-namespaces
  "[ns-sym ...] for every *_test.clj found (recursively) under the directory
   named by `root-segments` (path segments relative to the module working
   directory), namespaced under `base-ns`."
  [{:keys [root-segments base-ns]}]
  (let [root-dir (apply io/file root-segments)]
    (when-not (.isDirectory root-dir)
      (throw (ex-info "test root directory not found" {:root (.getPath root-dir)})))
    (->> (file-seq root-dir)
         (filter test-file?)
         (map #(file->namespace root-dir base-ns %))
         (sort-by str)
         vec)))

(defn- requested-namespaces [property-name]
  (when-let [raw (some-> property-name System/getProperty)]
    (->> (str/split raw #",") (map str/trim) (remove str/blank?) (map symbol) set)))

(defn- select-namespaces [namespaces only]
  (if (seq only)
    (let [selected (filterv only namespaces)
          missing (seq (remove (set namespaces) only))]
      (when missing (throw (ex-info "requested test namespaces were not discovered" {:missing (vec missing)})))
      selected)
    namespaces))

(defn run-tests!
  "Discover, require, and run clojure.test namespaces.

  Options:
  - `:root-segments`: path segments, relative to the module working
    directory, that point at the module's test namespace root.
  - `:base-ns`: namespace prefix represented by `:root-segments`.
  - `:only-property`: optional system property with a comma-separated list of
    fully-qualified test namespaces to run.
  - `:allow-empty?`: true when a target intentionally has no tests yet."
  [{:keys [only-property allow-empty?] :as opts}]
  (let [namespaces (discover-test-namespaces opts)
        selected (select-namespaces namespaces (requested-namespaces only-property))]
    (if-not (seq selected)
      (if allow-empty?
        (do (println "No Clojure test namespaces selected.") {:test 0 :pass 0 :fail 0 :error 0})
        (throw (ex-info "no test namespaces selected" opts)))
      (do
        (println "Discovered" (count namespaces) "test namespaces; running" (count selected))
        (doseq [ns-sym selected] (require ns-sym))
        (let [result (apply test/run-tests selected)]
          (when (pos? (+ (long (:fail result)) (long (:error result)))) (System/exit 1))
          result)))))
