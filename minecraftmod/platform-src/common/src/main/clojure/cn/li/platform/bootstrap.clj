(ns cn.li.platform.bootstrap
  "Canonical platform target bootstrap.

  The built artifact contains META-INF/academy-target.edn generated from
  platform-targets.json. Loader entrypoints call this namespace directly; there
  is no platform ServiceLoader indirection."
  (:require [cn.li.platform.target :as target]))

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
  (let [target-model (target/current-target!)
        {:keys [loader entrypoint]} target-model]
    (verify-capability-owners! target-model)
    (case loader
      "forge" ((require-resolve! (:namespace entrypoint) (:function entrypoint)))
      "fabric" ((require-resolve! (:namespace entrypoint) (:function entrypoint)))
      (throw (ex-info "Unsupported platform loader"
                      {:loader loader
                       :target (:id target-model)})))))
