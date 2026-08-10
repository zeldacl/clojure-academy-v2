(ns cn.li.platform.neutral.network-runtime
  (:require [clojure.string :as str]))
(def ^:private operations [:encode :decode :list-descriptors :handle-request])
(defn- unavailable [operation] (throw (IllegalStateException. (str "Network runtime provider unavailable: " operation))))
(doseq [operation operations] (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(defn install! [provided]
  (when (or (not= (set operations) (set (keys provided))) (some (complement ifn?) (vals provided)))
    (throw (ex-info "Network runtime provider contract mismatch" {:actual (sort (keys provided))})))
  (doseq [operation operations] (alter-var-root (ns-resolve *ns* (symbol (name operation))) (constantly (get provided operation)))) nil)
