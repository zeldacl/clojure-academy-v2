(ns cn.li.mcmod.content
  "Explicit suite content registration driven by generated target metadata."
  (:require [cn.li.platform.target :as target]))

(defn- require-resolve! [namespace-name symbol-name]
  (let [ns-sym (symbol namespace-name) var-sym (symbol symbol-name)]
    (require ns-sym)
    (or (ns-resolve ns-sym var-sym)
        (throw (ex-info "Content entrypoint missing"
                        {:namespace namespace-name :symbol symbol-name})))))

(defn register-content! [content-module]
  "Register one content module from generated {:id :namespace :function} metadata."
  (let [{:keys [namespace function]} content-module]
    (when-not (and namespace function)
      (throw (ex-info "Invalid content module metadata" {:module content-module})))
    ((require-resolve! namespace function)))
  nil)

(defn- declared-content-modules []
  (try (:content-modules (target/current-target!))
       (catch clojure.lang.ExceptionInfo _ [])))

(defn available-content-ids
  "Return content ids declared by the selected target metadata."
  []
  (mapv :id (declared-content-modules)))

(defn register-all-content!
  "Register every content module declared by the selected target metadata."
  []
  (doseq [content-module (declared-content-modules)]
    (register-content! content-module))
  nil)
