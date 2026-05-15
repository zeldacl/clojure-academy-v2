(ns cn.li.ac.discovery.loader
  "Safe namespace loader used by discovery systems."
  (:require [cn.li.mcmod.util.log :as log]))

(defn require-namespaces!
  "Require all namespaces. Returns {:loaded [...], :failed [{:ns ... :error ...}]}."
  [ns-syms]
  (reduce
    (fn [{:keys [loaded failed]} ns-sym]
      (try
        (require ns-sym)
        {:loaded (conj loaded ns-sym)
         :failed failed}
        (catch Throwable t
          (log/error "Discovery require failed" ns-sym (.getMessage t))
          {:loaded loaded
           :failed (conj failed {:ns ns-sym :error (.getMessage t)})})))
    {:loaded [] :failed []}
    ns-syms))
