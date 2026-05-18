(ns cn.li.mc1201.datagen.provider-registration
  "Shared datagen provider registration loop.

  Platform setup namespaces provide only the Loader/API-specific registration
  callback; provider ordering, labels, and summary output remain in the shared
  provider manifest."
  (:require [cn.li.mc1201.datagen.provider-manifest :as provider-manifest]))

(defn register-providers!
  "Register all logical datagen providers for `platform-key`.

  Options:
  - `:mod-id` string/keyword used in log messages.
  - `:register-provider!` function called with each provider manifest entry.

  Returns the vector of provider entries that were registered."
  [platform-key {:keys [mod-id register-provider!]}]
  (when-not register-provider!
    (throw (ex-info "Datagen provider registration requires :register-provider!"
                    {:platform platform-key})))
  (let [providers (provider-manifest/providers-for platform-key)
        mod-id (str mod-id)]
    (doseq [provider providers]
      (println (provider-manifest/registering-message mod-id provider))
      (register-provider! provider))
    (println (provider-manifest/summary-message mod-id platform-key))
    providers))
