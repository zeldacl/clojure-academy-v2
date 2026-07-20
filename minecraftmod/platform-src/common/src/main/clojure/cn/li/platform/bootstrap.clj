(ns cn.li.platform.bootstrap
  "Canonical platform target bootstrap.

  The built artifact contains META-INF/academy-target.edn generated from
  platform-targets.json. Loader entrypoints call this namespace directly; there
  is no platform ServiceLoader indirection."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private target-resource "META-INF/academy-target.edn")

(defn- read-target!
  []
  (if-let [resource (io/resource target-resource)]
    (with-open [reader (io/reader resource)]
      (edn/read {:readers *data-readers*} reader))
    (throw (ex-info "Platform target metadata missing"
                    {:resource target-resource}))))

(defn- require-resolve!
  [namespace-name symbol-name]
  (let [ns-sym (symbol namespace-name)
        var-sym (symbol symbol-name)]
    (require ns-sym)
    (or (ns-resolve ns-sym var-sym)
        (throw (ex-info "Platform target entrypoint missing"
                        {:namespace namespace-name
                         :symbol symbol-name})))))

(defn- verify-capability-owners!
  [{:keys [id capabilities capability-owners]}]
  (let [owners (into {}
                     (map (fn [[cap owner-list]]
                            [(if (keyword? cap) (name cap) (str cap)) owner-list]))
                     (or capability-owners {}))
        missing (remove #(contains? owners %) capabilities)
        duplicate (keep (fn [[cap owner-list]]
                          (when (not= 1 (count owner-list))
                            [cap owner-list]))
                        owners)]
    (when (seq missing)
      (throw (ex-info "Platform target has capabilities without owners"
                      {:target id
                       :missing (vec missing)})))
    (when (seq duplicate)
      (throw (ex-info "Platform target capability must have exactly one owner"
                      {:target id
                       :duplicate (into {} duplicate)})))))

(defn start!
  "Initialize the selected platform target exactly once."
  []
  (let [target (read-target!)
        {:keys [loader entrypoint]} target]
    (verify-capability-owners! target)
    (case loader
      "forge" ((require-resolve! (:namespace entrypoint) (:function entrypoint)))
      "fabric" ((require-resolve! (:namespace entrypoint) (:function entrypoint)))
      (throw (ex-info "Unsupported platform loader"
                      {:loader loader
                       :target (:id target)})))))
