(ns cn.li.platform.neutral.block-runtime
  (:require [clojure.string :as str]))

(def ^:private operations
  [:get-block-spec :list-all-blocks :identify-block-from-full-name :is-part-block?
   :has-block-event-handler? :snapshot-tiles-by-id :register-tile-capability-keys!
   :merge-tile-kind-defaults :create-property-registry :register-block-properties!
   :get-property :get-all-properties])

(defn- unavailable [operation]
  (throw (IllegalStateException. (str "Block runtime provider unavailable: " operation))))
(doseq [operation operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))

(defn install! [provided]
  (let [expected (set operations)]
    (when (or (not= expected (set (keys provided)))
              (some (complement ifn?) (vals provided)))
      (throw (ex-info "Block runtime provider contract mismatch"
                      {:actual (sort (keys provided))})))
    (doseq [operation operations]
      (alter-var-root (ns-resolve *ns* (symbol (name operation)))
                      (constantly (get provided operation)))))
  nil)
