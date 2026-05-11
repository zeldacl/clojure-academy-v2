(ns cn.li.mc1201.config.gameplay-bridge
  "Shared gameplay-config bridge holder.

  Platform modules install a provider map of gameplay access fns.
  Shared/business code can call this namespace without depending on loader config APIs."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private default-provider
  {:analysis-enabled? (constantly true)
   :attack-player? (constantly true)
   :destroy-blocks? (constantly true)
   :gen-ores? (constantly true)
   :gen-phase-liquid? (constantly true)
   :heads-or-tails? (constantly false)
   :get-normal-metal-blocks (constantly [])
   :get-weak-metal-blocks (constantly [])
   :get-metal-entities (constantly [])
   :is-normal-metal-block? (constantly false)
   :is-weak-metal-block? (constantly false)
   :is-metal-block? (constantly false)
   :is-metal-entity? (constantly false)
   :get-cp-recover-cooldown (constantly 0)
   :get-cp-recover-speed (constantly 0.0)
   :get-overload-recover-cooldown (constantly 0)
   :get-overload-recover-speed (constantly 0.0)
   :get-init-cp (constantly 0)
   :get-add-cp (constantly 0)
   :get-init-overload (constantly 0)
   :get-add-overload (constantly 0)
   :get-damage-scale (constantly 1.0)})

(defonce ^:private provider* (atom default-provider))

(defn install-provider!
  [provider-map]
  (when (map? provider-map)
    (swap! provider* merge provider-map)
    (log/info "Installed shared gameplay config provider" {:keys (count provider-map)}))
  nil)

(defn provider [] @provider*)

(defn- call0 [k]
  ((or (get @provider* k) (get default-provider k))))

(defn- call1 [k x]
  ((or (get @provider* k) (get default-provider k)) x))

(defn analysis-enabled? [] (call0 :analysis-enabled?))
(defn attack-player? [] (call0 :attack-player?))
(defn destroy-blocks? [] (call0 :destroy-blocks?))
(defn gen-ores? [] (call0 :gen-ores?))
(defn gen-phase-liquid? [] (call0 :gen-phase-liquid?))
(defn heads-or-tails? [] (call0 :heads-or-tails?))
(defn get-normal-metal-blocks [] (vec (call0 :get-normal-metal-blocks)))
(defn get-weak-metal-blocks [] (vec (call0 :get-weak-metal-blocks)))
(defn get-metal-entities [] (vec (call0 :get-metal-entities)))
(defn is-normal-metal-block? [block-id] (boolean (call1 :is-normal-metal-block? block-id)))
(defn is-weak-metal-block? [block-id] (boolean (call1 :is-weak-metal-block? block-id)))
(defn is-metal-block? [block-id] (boolean (call1 :is-metal-block? block-id)))
(defn is-metal-entity? [entity-id] (boolean (call1 :is-metal-entity? entity-id)))
(defn get-cp-recover-cooldown [] (call0 :get-cp-recover-cooldown))
(defn get-cp-recover-speed [] (call0 :get-cp-recover-speed))
(defn get-overload-recover-cooldown [] (call0 :get-overload-recover-cooldown))
(defn get-overload-recover-speed [] (call0 :get-overload-recover-speed))
(defn get-init-cp [level] (call1 :get-init-cp level))
(defn get-add-cp [level] (call1 :get-add-cp level))
(defn get-init-overload [level] (call1 :get-init-overload level))
(defn get-add-overload [level] (call1 :get-add-overload level))
(defn get-damage-scale [] (call0 :get-damage-scale))

(defn bind-gameplay-config!
  [provider-map]
  (try
    (require 'cn.li.ac.config.gameplay)
    (install-provider! provider-map)
    (let [config-var (ns-resolve 'cn.li.ac.config.gameplay '*config-bridge*)]
      (when config-var
        (alter-var-root config-var (constantly provider-map))
        (log/info "Shared gameplay config bridge bound successfully" {:keys (count provider-map)})))
    (catch Exception e
      (log/error "Failed to bind shared gameplay config bridge" e))))
