(ns cn.li.platform.neutral.client-render
  (:require [clojure.string :as str]))
(def ^:private operations [:initial-button-state :handle-button-state!])
(defn- unavailable [operation] (throw (IllegalStateException. (str "Client render provider unavailable: " operation))))
(def initial-button-state (fn [& _] (unavailable :initial-button-state)))
(def handle-button-state! (fn [& _] (unavailable :handle-button-state!)))
(doseq [operation operations] (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(defn- facade-var [operation]
  (let [facade-ns (the-ns 'cn.li.platform.neutral.client-render)
        operation-symbol (symbol (name operation))]
    (or (ns-resolve facade-ns operation-symbol)
        (intern facade-ns operation-symbol (fn [& _] (unavailable operation))))))
(defn install! [provided]
  (when (or (not= (set operations) (set (keys provided))) (some (complement ifn?) (vals provided)))
    (throw (ex-info "Client render provider contract mismatch" {:actual (sort (keys provided))})))
  (doseq [operation operations] (alter-var-root (facade-var operation) (constantly (get provided operation)))) nil)
