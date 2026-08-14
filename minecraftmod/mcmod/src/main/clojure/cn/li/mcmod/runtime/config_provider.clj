(ns cn.li.mcmod.runtime.config-provider
  "Neutral configuration provider for platform AOT adapters."
  (:require [cn.li.mcmod.config :as config]
            [cn.li.mcmod.config.registry :as registry]))

(defn runtime-provider
  [_]
  {:mod-id (constantly config/mod-id)
   :asset-path config/asset-path
   :namespaced-path config/namespaced-path
   :config-file-path config/config-file-path
   :get-all-config-domains registry/get-all-config-domains
   :get-config-descriptors registry/get-config-descriptors
   :get-config-values registry/get-config-values
   :set-config-value! registry/set-config-value!
   :set-config-values! registry/set-config-values!})
