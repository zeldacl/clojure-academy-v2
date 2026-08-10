(ns cn.li.platform.neutral.client-render
  (:require [clojure.string :as str]))
(def ^:private operations [:initial-button-state :handle-button-state! :validate-profile! :kind-renderer-key :register-scripted-effect-kind! :resolve-kind-renderer-key :supported-kinds :get-profile :snapshot])
(defn- unavailable [operation] (throw (IllegalStateException. (str "Client render provider unavailable: " operation))))
(doseq [operation operations] (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(defn install! [provided]
  (when (or (not= (set operations) (set (keys provided))) (some (complement ifn?) (vals provided)))
    (throw (ex-info "Client render provider contract mismatch" {:actual (sort (keys provided))})))
  (doseq [operation operations] (alter-var-root (ns-resolve *ns* (symbol (name operation))) (constantly (get provided operation)))) nil)
