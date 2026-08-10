(ns cn.li.platform.neutral.client-runtime
  (:require [clojure.string :as str]))

(def ^:private operations
  [:merge-client-bridge! :call-adapter :resolve-shader :open-screen!
   :reactive-overlay-build :reactive-overlay-update :reactive-overlay-mode-switch!
   :run-client-tick-hooks! :create-widget
   :register-default-renderer-init-fns! :register-all-renderers!
   :get-scripted-tile-renderer :scripted-renderers-snapshot
   :install-pose-ops! :install-render-buffer-ops!
   :register-texture! :get-texture-path :reset-texture-registry-for-test!])

(defn- unavailable [operation]
  (throw (IllegalStateException. (str "Client runtime provider unavailable: " operation))))

(doseq [operation operations]
  (intern *ns* (symbol (name operation)) (fn [& _] (unavailable operation))))

(defn install! [provided]
  (let [expected (set operations)]
    (when (or (not= expected (set (keys provided)))
              (some (complement ifn?) (vals provided)))
      (throw (ex-info "Client runtime provider contract mismatch"
                      {:actual (sort (keys provided))})))
    (doseq [operation operations]
      (alter-var-root (ns-resolve *ns* (symbol (name operation)))
                      (constantly (get provided operation)))))
  nil)
