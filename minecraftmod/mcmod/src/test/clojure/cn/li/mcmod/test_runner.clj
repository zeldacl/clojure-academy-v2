(ns cn.li.mcmod.test-runner
  "Auto-discovers mcmod unit test namespaces and runs clojure.test.
   Supports -Dmcmod.test.only=ns1,ns2 to run a subset."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t])
  (:gen-class))

(def ^:private test-root
  (io/file "src" "test" "clojure" "cn" "li" "mcmod"))

(defn- file->ns-sym
  [f]
  (let [root-path (.toPath test-root)
        rel (.toString (.relativize root-path (.toPath f)))
        rel (str/replace rel #"\.clj$" "")
        rel (str/replace rel #"[\\/]" ".")
        ns-name (str "cn.li.mcmod." rel)
        ns-name (str/replace ns-name #"_" "-")]
    (symbol ns-name)))

(defn- discover-test-namespaces
  []
  (->> (file-seq test-root)
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) "_test.clj"))
       (map file->ns-sym)
       sort
       vec))

(defn- selected-test-namespaces
  []
  (let [all-tests (discover-test-namespaces)
        only-str (System/getProperty "mcmod.test.only")
        only-set (when (seq only-str)
                   (->> (str/split only-str #",")
                        (map str/trim)
                        (remove str/blank?)
                        (map symbol)
                        set))]
    (if (seq only-set)
      (vec (filter only-set all-tests))
      all-tests)))

(defn -main [& _]
  (let [test-namespaces (selected-test-namespaces)]
    (doseq [ns-sym test-namespaces]
      (require ns-sym))
    (let [summary (apply t/run-tests test-namespaces)]
      (shutdown-agents)
      (let [fail (:fail summary 0)
            err (:error summary 0)]
        (when (or (pos? fail) (pos? err))
          (binding [*out* *err*]
            (println "Clojure test summary:" summary)))
        (System/exit (if (and (zero? fail) (zero? err)) 0 1))))))
