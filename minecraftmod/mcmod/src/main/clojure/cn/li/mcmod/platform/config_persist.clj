(ns cn.li.mcmod.platform.config-persist
  "Platform-injected single-value config persistence (Forge TOML / Fabric JSON)."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.platform.runtime :as prt]))

(def ^:private ^:dynamic *persist-config-value-fn* nil)

(defn install-config-persist-op!
  [persist-fn label]
  (prt/install-impl! #'*persist-config-value-fn* persist-fn (or label "config-persist")))

(defn config-persist-available?
  []
  (prt/impl-available? #'*persist-config-value-fn*))

(defn persist-config-value!
  "Persist one config entry through the platform adapter when installed.
  Always updates the in-memory registry for immediate getter visibility."
  [domain key value]
  (config-reg/set-config-values! domain {key value})
  (when-let [persist-fn (prt/impl-current #'*persist-config-value-fn*)]
    (persist-fn domain key value)))

(defn reset-config-persist-for-test!
  []
  (alter-var-root #'*persist-config-value-fn* (constantly nil))
  nil)
