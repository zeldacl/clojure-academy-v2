(ns cn.li.ac.energy.api.impl
  "Default Phase C energy API implementation.

  This namespace bridges the new protocol layer to the concrete item/node
  service implementations while keeping migration friction low."
  (:require [cn.li.ac.energy.api.protocol :as proto]
            [cn.li.ac.energy.service.provider-registry :as provider-registry]
            [cn.li.ac.energy.service.subscription :as subscription]
            [cn.li.ac.energy.service.transfer-executor :as transfer-executor]
            [cn.li.ac.energy.service.item-manager :as item-manager]
            [cn.li.ac.energy.service.node-manager :as node-manager]
            [cn.li.ac.energy.domain.container :as container]
            [cn.li.mcmod.util.log :as log]))

(defrecord EnergySystemImpl [providers subscriptions scheduled-transfers]
  proto/IEnergyManager
  (get-energy [_this id]
    (some-> (provider-registry/resolve-provider providers id) provider-registry/provider-energy))

  (get-capacity [_this id]
    (some-> (provider-registry/resolve-provider providers id) provider-registry/provider-capacity))

  (set-energy [_this id amount]
    (if-let [{:keys [kind value] :as provider} (provider-registry/resolve-provider providers id)]
      (let [old-value (provider-registry/provider-energy provider)]
        (case kind
          :item (do
                  (item-manager/set-item-energy! value amount)
                  (subscription/notify! subscriptions id old-value (item-manager/get-item-energy value))
                  {:success true :reason "ok"})
          :node (do
                  (node-manager/set-node-energy! value amount)
                  (subscription/notify! subscriptions id old-value (node-manager/get-node-energy value))
                  {:success true :reason "ok"})
          :container (if-let [[before after] (provider-registry/update-container-ref! provider container/set-energy amount)]
                       (do
                         (subscription/notify! subscriptions id (container/get-current before) (container/get-current after))
                         {:success true :reason "ok"})
                       {:success false :reason "container is immutable unless registered with an atom ref"})
          {:success false :reason "unsupported provider"}))
      {:success false :reason "provider not found"}))

  (transfer-energy [this source dest amount]
    (proto/transfer-energy this source dest amount nil))

  (transfer-energy [_this source dest amount callback]
    (transfer-executor/transfer!
      (provider-registry/resolve-provider providers source)
      (provider-registry/resolve-provider providers dest)
      amount
      callback))

  (drain-energy [_this id amount]
    (let [provider (provider-registry/resolve-provider providers id)
          old-value (some-> provider provider-registry/provider-energy)
          [success extracted] (transfer-executor/drain! provider amount)
          new-value (some-> provider provider-registry/provider-energy)]
      (when provider
        (subscription/notify! subscriptions id old-value new-value))
      [success extracted]))

  (subscribe-to-changes [_this id callback]
    (subscription/subscribe! subscriptions id callback))

  (unsubscribe-from-changes [_this sid]
    (subscription/unsubscribe! subscriptions sid))

  (list-energy-providers [_this]
    (provider-registry/provider-ids providers))

  proto/IEnergyItem
  (get-item-energy [_this item-stack]
    (item-manager/get-item-energy item-stack))
  (set-item-energy [_this item-stack amount]
    (item-manager/set-item-energy! item-stack amount)
    nil)
  (get-item-capacity [_this item-stack]
    (item-manager/get-item-capacity item-stack))
  (get-item-bandwidth [_this item-stack]
    (item-manager/get-item-bandwidth item-stack))
  (is-energy-item? [_this item-stack]
    (item-manager/is-energy-item-supported? item-stack))
  (charge-item [_this item-stack amount]
    (item-manager/charge-item item-stack amount))
  (discharge-item [_this item-stack amount]
    (item-manager/discharge-item item-stack amount))

  proto/IEnergyNode
  (get-node-energy [_this node-vblock]
    (node-manager/get-node-energy node-vblock))
  (set-node-energy [_this node-vblock amount]
    (node-manager/set-node-energy! node-vblock amount)
    nil)
  (get-node-capacity [_this node-vblock]
    (node-manager/get-node-capacity node-vblock))
  (inject-energy [_this node-vblock amount]
    (node-manager/inject-energy node-vblock amount))
  (extract-node-energy [_this node-vblock amount]
    (node-manager/extract-node-energy node-vblock amount))

  proto/IEnergyValidator
  (validate-energy-amount [_this amount]
    {:valid (and (number? amount) (Double/isFinite (double amount)) (>= (double amount) 0.0))
     :errors (cond-> []
               (not (number? amount))
               (conj "amount must be numeric")
               (and (number? amount) (not (Double/isFinite (double amount))))
               (conj "amount must be finite")
               (and (number? amount) (< (double amount) 0.0))
               (conj "amount must be non-negative"))})
  (validate-transfer [this source dest amount]
    (let [amount-validation (proto/validate-energy-amount this amount)]
      {:valid (and (:valid amount-validation)
                   (some? (provider-registry/resolve-provider providers source))
                   (some? (provider-registry/resolve-provider providers dest)))
       :reason (cond
                 (not (:valid amount-validation)) (first (:errors amount-validation))
                 (nil? (provider-registry/resolve-provider providers source)) "source not found"
                 (nil? (provider-registry/resolve-provider providers dest)) "destination not found"
                 :else "ok")}))
  (validate-network-consistency [_this _network-id]
    {:consistent true :errors []})
  (repair-inconsistency [_this _network-id]
    {:repaired false :changes []})

  proto/IEnergyAdmin
  (admin-dump-state [_this]
    (provider-registry/dump-state providers))
  (admin-set-energy-unsafe [this id amount reason]
    (log/warn (str "Unsafe energy set for " id ": " amount " because " reason))
    (proto/set-energy this id amount))
  (admin-reset-energy-system [_this]
    (provider-registry/clear! providers)
    (subscription/clear! subscriptions)
    (reset! scheduled-transfers {})
    nil)
  (admin-simulate-loss [_this _network-id amount-lost reason]
    (log/warn (str "Simulated energy loss: " amount-lost " because " reason))
    {:lost amount-lost}))

(defonce ^:private default-energy-system*
  (delay (->EnergySystemImpl (atom {}) (atom {}) (atom {}))))

(defn energy-system
  "Return the default energy system implementation."
  []
  @default-energy-system*)

(defn register-provider!
  "Register a provider under a stable id.

  The value may be:
  - an item stack
  - a wireless node tile entity
  - an EnergyContainer
  - an atom holding an EnergyContainer"
  [id value]
  (provider-registry/register-provider! (:providers (energy-system)) id value))

(defn unregister-provider!
  "Unregister a previously registered provider id."
  [id]
  (provider-registry/unregister-provider! (:providers (energy-system)) id))
