(ns cn.li.mcmod.spi.keybinding-registry
  "Neutral keybinding configuration registry.

  Problem: forge-1.20.1/client/key_mapping_adapter.clj was directly
  calling `cn.li.ac.input-ids/get-input-ids` — a static dependency on
  a business content module.

  Solution: Content modules register their keybinding configurations
  into this neutral registry. Platform key-mapping adapters read from
  the registry without knowing which content modules exist."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private keybinding-configs (atom {}))

(defn register-keybinding-configs!
  "Register keybinding configurations from a content module.

  configs must be a map of {input-id -> config-map} where each
  config-map has keys like :scheme, :input-id, :key-mapping.

  Called by content modules (e.g. AC) during bootstrap."
  [module-label configs]
  (assert (map? configs)
          (str "keybinding configs must be map?, got " (type configs)))
  (swap! keybinding-configs into configs)
  (log/info "Keybinding configs registered from" module-label
            "(+" (count configs) "bindings, total:" (count @keybinding-configs) ")")
  nil)

(defn get-all-keybinding-configs
  "Returns all registered keybinding configurations.
  Called by platform key-mapping adapters."
  []
  @keybinding-configs)

(defn registered-keybinding-count
  "Returns the total count of registered keybindings (for diagnostics)."
  []
  (count @keybinding-configs))
