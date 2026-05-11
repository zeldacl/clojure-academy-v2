(ns cn.li.mc1201.config.gameplay-init
  "Shared gameplay-config bridge binder.

  Platform modules pass a provider map (loader-specific source), then this namespace
  binds it into business-layer gameplay config dynamic bridge."
  (:require [cn.li.mc1201.config.gameplay-bridge :as gameplay-bridge]
            [cn.li.mcmod.util.log :as log]))

(defn bind-gameplay-config!
  [provider-map]
  (try
    (require 'cn.li.ac.config.gameplay)
    (gameplay-bridge/install-provider! provider-map)
    (let [config-var (ns-resolve 'cn.li.ac.config.gameplay '*config-bridge*)]
      (when config-var
        (alter-var-root config-var (constantly provider-map))
        (log/info "Shared gameplay config bridge bound successfully" {:keys (count provider-map)})))
    (catch Exception e
      (log/error "Failed to bind shared gameplay config bridge" e))))
