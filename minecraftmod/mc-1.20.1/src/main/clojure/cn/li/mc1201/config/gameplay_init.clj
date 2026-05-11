(ns cn.li.mc1201.config.gameplay-init
  "Shared gameplay-config bridge binder.

  Provides helpers to build and bind platform-specific config bridges
  into business-layer gameplay config via dynamic var injection."
  (:require [cn.li.mc1201.config.gameplay-bridge :as gameplay-bridge]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Config Bridge Map Building
;; ============================================================================

(def ^:private CONFIG-BRIDGE-KEYS
  "Config bridge entry point keys that must be provided by platform bridges."
  [:analysis-enabled? :attack-player? :destroy-blocks? :gen-ores?
   :gen-phase-liquid? :heads-or-tails? :get-normal-metal-blocks
   :get-weak-metal-blocks :get-metal-entities :is-normal-metal-block?
   :is-weak-metal-block? :is-metal-block? :is-metal-entity?
   :get-cp-recover-cooldown :get-cp-recover-speed
   :get-overload-recover-cooldown :get-overload-recover-speed
   :get-init-cp :get-add-cp :get-init-overload :get-add-overload
   :get-damage-scale])

(defn build-config-bridge
  "Build config bridge map from a platform-specific bridge namespace.

  Args:
    bridge-ns-sym: Symbol naming the bridge namespace (e.g., 'cn.li.forge1201.config.gameplay-bridge)

  Returns:
    Map of config-bridge keys to platform bridge functions"
  [bridge-ns-sym]
  (require bridge-ns-sym)
  (let [bridge-ns (find-ns bridge-ns-sym)]
    (into {}
      (for [key CONFIG-BRIDGE-KEYS]
        (let [var-sym (symbol (name key))]
          (if-let [bridge-fn (ns-resolve bridge-ns var-sym)]
            [key bridge-fn]
            (do
              (log/warn "Config bridge key not found in" bridge-ns-sym ":" key)
              nil)))))))

;; ============================================================================
;; Config Bridge Binding
;; ============================================================================

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
