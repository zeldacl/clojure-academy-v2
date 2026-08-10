(ns cn.li.platform.neutral.event-runtime
  (:require [clojure.string :as str]))

(def ^:private operations
  [:on-block-right-click :on-block-place :on-block-break
   :interaction-consumed? :gui-open-result?
   :dispatch-world-load :dispatch-world-unload :dispatch-world-save :dispatch-world-tick
   :remember-saved-data! :consume-saved-data! :clear-world-saved-data!
   :set-on-world-state-changed-fn! :world-key])

(defn- unavailable [operation]
  (throw (IllegalStateException. (str "Event runtime provider unavailable: " operation))))
(doseq [operation operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))

(defn- facade-var [operation]
  (let [facade-ns (the-ns 'cn.li.platform.neutral.event-runtime)
        operation-symbol (symbol (name operation))]
    (or (ns-resolve facade-ns operation-symbol)
        (intern facade-ns operation-symbol (fn [& _] (unavailable operation))))))

(defn install! [provided]
  (let [expected (set operations)]
    (when (or (not= expected (set (keys provided)))
              (some (complement ifn?) (vals provided)))
      (throw (ex-info "Event runtime provider contract mismatch"
                      {:actual (sort (keys provided))})))
    (doseq [operation operations]
      (alter-var-root (facade-var operation)
                      (constantly (get provided operation)))))
  nil)
