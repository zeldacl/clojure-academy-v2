(ns cn.li.ac.energy.api.impl
  "Default Phase C energy API implementation.

  This namespace bridges the new protocol layer to the concrete item/node
  service implementations while keeping migration friction low."
  (:require [cn.li.ac.energy.api.protocol :as proto]
            [cn.li.ac.energy.service.item-manager :as item-manager]
            [cn.li.ac.energy.service.node-manager :as node-manager]
            [cn.li.ac.energy.domain.container :as container]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util UUID]))

(defn- subscription-id []
  (str (UUID/randomUUID)))

(defn- infer-provider
  [value]
  (cond
    (nil? value) nil
    (and (map? value) (:kind value)) value
    (item-manager/is-energy-item-supported? value) {:kind :item :value value}
    (node-manager/is-node-supported? value) {:kind :node :value value}
    (container/energy-container? value) {:kind :container :value value}
    :else nil))

(defn- resolve-provider
  [providers id-or-value]
  (or (get @providers id-or-value)
      (when (keyword? id-or-value) (get @providers (name id-or-value)))
      (infer-provider id-or-value)))

(defn- provider-energy
  [{:keys [kind value ref]}]
  (case kind
    :item (item-manager/get-item-energy value)
    :node (or (node-manager/get-node-energy value) 0.0)
    :container (container/get-current (if ref @ref value))
    nil))

(defn- provider-capacity
  [{:keys [kind value ref]}]
  (case kind
    :item (item-manager/get-item-capacity value)
    :node (or (node-manager/get-node-capacity value) 0.0)
    :container (container/get-capacity (if ref @ref value))
    nil))

(defn- update-container-ref!
  [{:keys [ref value]} f & args]
  (let [target (or ref value)]
    (when (instance? clojure.lang.IAtom target)
      (let [old @target
            new-value (apply f old args)]
        (reset! target new-value)
        [old new-value]))))

(defn- notify-subscribers!
  [subscriptions source-id old-value new-value]
  (doseq [[_ {:keys [id callback]}] @subscriptions]
    (when (= id source-id)
      (try
        (callback old-value new-value)
        (catch Exception e
          (log/warn (str "Energy subscription callback failed for " source-id ": " (.getMessage e))))))))

(defrecord EnergySystemImpl [providers subscriptions scheduled-transfers]
  proto/IEnergyManager
  (get-energy [_this id]
    (some-> (resolve-provider providers id) provider-energy))

  (get-capacity [_this id]
    (some-> (resolve-provider providers id) provider-capacity))

  (set-energy [_this id amount]
    (if-let [{:keys [kind value] :as provider} (resolve-provider providers id)]
      (let [old-value (provider-energy provider)]
        (case kind
          :item (do
                  (item-manager/set-item-energy! value amount)
                  (notify-subscribers! subscriptions id old-value (item-manager/get-item-energy value))
                  {:success true :reason "ok"})
          :node (do
                  (node-manager/set-node-energy! value amount)
                  (notify-subscribers! subscriptions id old-value (node-manager/get-node-energy value))
                  {:success true :reason "ok"})
          :container (if-let [[before after] (update-container-ref! provider container/set-energy amount)]
                       (do
                         (notify-subscribers! subscriptions id (container/get-current before) (container/get-current after))
                         {:success true :reason "ok"})
                       {:success false :reason "container is immutable unless registered with an atom ref"})
          {:success false :reason "unsupported provider"}))
      {:success false :reason "provider not found"}))

  (transfer-energy [this source dest amount]
    (proto/transfer-energy this source dest amount nil))

  (transfer-energy [_this source dest amount callback]
    (let [source-provider (resolve-provider providers source)
          dest-provider (resolve-provider providers dest)]
      (cond
        (not (and source-provider dest-provider))
        {:transferred 0.0 :lost 0.0 :reason "provider not found"}

        (not (pos? (double amount)))
        {:transferred 0.0 :lost 0.0 :reason "amount must be positive"}

        :else
        (let [extracted (case (:kind source-provider)
                          :item (item-manager/discharge-item (:value source-provider) amount)
                          :node (node-manager/extract-node-energy (:value source-provider) amount)
                          :container 0.0
                          0.0)
              leftover (case (:kind dest-provider)
                         :item (item-manager/charge-energy-to-item (:value dest-provider) extracted false)
                         :node (node-manager/charge-node (:value dest-provider) extracted false)
                         :container extracted
                         extracted)
              transferred (- extracted leftover)
              result {:transferred transferred :lost 0.0 :reason "ok"}]
          (when (and (pos? leftover) (pos? extracted))
            (case (:kind source-provider)
              :item (item-manager/charge-energy-to-item (:value source-provider) leftover false)
              :node (node-manager/charge-node (:value source-provider) leftover false)
              nil))
          (when callback
            (callback result))
          result))))

  (drain-energy [_this id amount]
    (if-let [{:keys [kind value] :as provider} (resolve-provider providers id)]
      (let [old-value (provider-energy provider)
            extracted (case kind
                        :item (item-manager/discharge-item value amount)
                        :node (node-manager/extract-node-energy value amount)
                        :container 0.0
                        0.0)
            new-value (provider-energy provider)]
        (notify-subscribers! subscriptions id old-value new-value)
        [(pos? extracted) extracted])
      [false 0.0]))

  (subscribe-to-changes [_this id callback]
    (let [sid (subscription-id)]
      (swap! subscriptions assoc sid {:id id :callback callback})
      sid))

  (unsubscribe-from-changes [_this sid]
    (swap! subscriptions dissoc sid)
    nil)

  (list-energy-providers [_this]
    (vec (keys @providers)))

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
                   (some? (resolve-provider providers source))
                   (some? (resolve-provider providers dest)))
       :reason (cond
                 (not (:valid amount-validation)) (first (:errors amount-validation))
                 (nil? (resolve-provider providers source)) "source not found"
                 (nil? (resolve-provider providers dest)) "destination not found"
                 :else "ok")}))
  (validate-network-consistency [_this _network-id]
    {:consistent true :errors []})
  (repair-inconsistency [_this _network-id]
    {:repaired false :changes []})

  proto/IEnergyAdmin
  (admin-dump-state [_this]
    (into {}
          (map (fn [[id provider]]
                 [id {:kind (:kind provider)
                      :energy (provider-energy provider)
                      :capacity (provider-capacity provider)}]))
          @providers))
  (admin-set-energy-unsafe [this id amount reason]
    (log/warn (str "Unsafe energy set for " id ": " amount " because " reason))
    (proto/set-energy this id amount))
  (admin-reset-energy-system [_this]
    (reset! providers {})
    (reset! subscriptions {})
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
  (let [provider (cond
                   (instance? clojure.lang.IAtom value)
                   (if-let [nested (infer-provider @value)]
                     (assoc nested :ref value)
                     {:kind :container :ref value :value @value})

                   :else
                   (infer-provider value))]
    (when provider
      (swap! (:providers (energy-system)) assoc id provider)
      id)))

(defn unregister-provider!
  "Unregister a previously registered provider id."
  [id]
  (swap! (:providers (energy-system)) dissoc id)
  nil)
