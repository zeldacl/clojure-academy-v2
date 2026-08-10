(ns cn.li.platform.neutral.command-runtime
  (:require [clojure.string :as str]))

(def ^:private operations [:create-context :execute :execute-action-impl
                          :get-all-command-ids :get-command-spec :init-commands!])
(defn- unavailable [operation]
  (throw (IllegalStateException. (str "Command runtime provider unavailable: " operation))))
(doseq [operation operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(defn- facade-var [operation]
  (let [facade-ns (the-ns 'cn.li.platform.neutral.command-runtime)
        operation-symbol (symbol (name operation))]
    (or (ns-resolve facade-ns operation-symbol)
        (intern facade-ns operation-symbol (fn [& _] (unavailable operation))))))
(defn install! [provided]
  (let [expected (set operations)]
    (when (or (not= expected (set (keys provided)))
              (some (complement ifn?) (vals provided)))
      (throw (ex-info "Command runtime provider contract mismatch" {:actual (sort (keys provided))})))
    (doseq [operation operations]
      (alter-var-root (facade-var operation) (constantly (get provided operation)))))
  nil)
