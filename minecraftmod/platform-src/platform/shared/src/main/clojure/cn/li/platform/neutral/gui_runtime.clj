(ns cn.li.platform.neutral.gui-runtime
  (:require [clojure.string :as str]))

(def ^:private operations
  [:get-all-gui-ids :get-registry-name :get-display-name :get-slot-layout :get-slot-range
   :get-screen-factory-fn :get-screen-factory-fn-kw :get-gui-handler :get-server-container
   :get-container-for-menu :owner-from-container :register-menu-container! :unregister-menu-container!
   :get-gui-id-for-container :safe-close! :safe-validate :server-menu-sync! :slot-can-place?
   :slot-changed! :slot-count :slot-get-item :slot-set-item! :clamp-int :get-slot-validator])
(defn- unavailable [operation]
  (throw (IllegalStateException. (str "GUI runtime provider unavailable: " operation))))
(doseq [operation operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))
(defn install! [provided]
  (let [expected (set operations)]
    (when (or (not= expected (set (keys provided))) (some (complement ifn?) (vals provided)))
      (throw (ex-info "GUI runtime provider contract mismatch" {:actual (sort (keys provided))})))
    (doseq [operation operations]
      (alter-var-root (ns-resolve *ns* (symbol (name operation))) (constantly (get provided operation)))))
  nil)
