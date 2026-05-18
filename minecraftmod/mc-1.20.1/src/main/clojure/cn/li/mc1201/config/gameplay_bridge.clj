(ns cn.li.mc1201.config.gameplay-bridge
  "Shared gameplay-config bridge holder.

  Platform modules install a provider map of gameplay access fns.
  Shared/business code can call this namespace without depending on loader config APIs.

  Provider contract and helper builders live here so Forge/Fabric keep only raw
  storage access while level fallback and predicate semantics stay aligned."
  (:require [cn.li.mcmod.util.log :as log]))

(def provider-keys
  "Canonical gameplay provider key set shared by platform adapters and AC."
  [:attack-player?
   :destroy-blocks?
   :get-normal-metal-blocks
   :get-weak-metal-blocks
   :get-metal-entities
   :is-normal-metal-block?
   :is-weak-metal-block?
   :is-metal-block?
   :is-metal-entity?
   :get-cp-recover-cooldown
   :get-cp-recover-speed
   :get-overload-recover-cooldown
   :get-overload-recover-speed
   :get-init-cp
   :get-add-cp
   :get-init-overload
   :get-add-overload
   :get-damage-scale])

(def ^:private default-provider
  {:attack-player? (constantly true)
   :destroy-blocks? (constantly true)
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

(defn level-value
  "Read a level-indexed numeric list using the AC/Fabric fallback rule: out of
  range or non-numeric levels return 0 instead of leaking platform defaults."
  [values level]
  (let [idx (if (number? level) (int level) -1)]
    (get (vec (or values [])) idx 0)))

(defn list-predicate
  "Build a predicate over a dynamic string list getter."
  [values-fn]
  (fn [id]
    (contains? (set (map str (values-fn))) (str id))))

(defn make-provider-map
  "Build a canonical provider map from raw platform config getter fns."
  [{:keys [attack-player?
           destroy-blocks?
           get-normal-metal-blocks
           get-weak-metal-blocks
           get-metal-entities
           get-cp-recover-cooldown
           get-cp-recover-speed
           get-overload-recover-cooldown
           get-overload-recover-speed
           get-init-cp-list
           get-add-cp-list
           get-init-overload-list
           get-add-overload-list
           get-damage-scale]}]
  (let [normal-metal? (list-predicate get-normal-metal-blocks)
        weak-metal? (list-predicate get-weak-metal-blocks)
        metal-entity? (list-predicate get-metal-entities)]
    {:attack-player? attack-player?
     :destroy-blocks? destroy-blocks?
     :get-normal-metal-blocks get-normal-metal-blocks
     :get-weak-metal-blocks get-weak-metal-blocks
     :get-metal-entities get-metal-entities
     :is-normal-metal-block? normal-metal?
     :is-weak-metal-block? weak-metal?
     :is-metal-block? (fn [block-id]
                        (or (normal-metal? block-id)
                            (weak-metal? block-id)))
     :is-metal-entity? metal-entity?
     :get-cp-recover-cooldown get-cp-recover-cooldown
     :get-cp-recover-speed get-cp-recover-speed
     :get-overload-recover-cooldown get-overload-recover-cooldown
     :get-overload-recover-speed get-overload-recover-speed
     :get-init-cp #(level-value (get-init-cp-list) %)
     :get-add-cp #(level-value (get-add-cp-list) %)
     :get-init-overload #(level-value (get-init-overload-list) %)
     :get-add-overload #(level-value (get-add-overload-list) %)
     :get-damage-scale get-damage-scale}))

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

(defn attack-player? [] (call0 :attack-player?))
(defn destroy-blocks? [] (call0 :destroy-blocks?))
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
