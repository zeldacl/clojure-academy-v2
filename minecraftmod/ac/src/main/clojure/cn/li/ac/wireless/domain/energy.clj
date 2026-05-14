(ns cn.li.ac.wireless.domain.energy
  "Wireless energy domain model - pure data.
  
  Represents energy storage, transfer, and balance mechanics.
  Pure data structure with no implementation."
  (:require [cn.li.ac.foundation.validation :as val]))

;; Energy Container record
(defrecord EnergyContainer [current max-capacity transfer-rate efficiency last-updated])

;; Energy Transfer record
(defrecord EnergyTransfer [source-id dest-id amount efficiency timestamp])

;; ============================================================================
;; Energy Container Operations (Pure)
;; ============================================================================

(defn create-energy-container
  "Create new energy container."
  [max-capacity & [{:keys [efficiency] :or {efficiency 1.0}}]]
  (->EnergyContainer 0.0 (double max-capacity) 0.0 (double (max 0.0 (min 1.0 efficiency))) (System/currentTimeMillis)))

(defn receive-energy
  "Add energy to container, returns [new-container amount-accepted]."
  [container amount]
  (let [space-available (- (:max-capacity container) (:current container))
        amount-to-add (min amount space-available)
        new-current (+ (:current container) amount-to-add)]
    [(assoc container :current new-current :last-updated (System/currentTimeMillis)) amount-to-add]))

(defn extract-energy
  "Remove energy from container, returns [new-container amount-extracted]."
  [container amount]
  (let [available (min amount (:current container))
        new-current (- (:current container) available)]
    [(assoc container :current new-current :last-updated (System/currentTimeMillis)) available]))

(defn set-energy
  "Set energy to exact amount."
  [container amount]
  (let [clamped (max 0.0 (min (:max-capacity container) amount))]
    (assoc container :current clamped :last-updated (System/currentTimeMillis))))

(defn drain-completely
  "Remove all energy from container."
  [container]
  [(assoc container :current 0.0 :last-updated (System/currentTimeMillis)) (:current container)])

;; ============================================================================
;; Energy Transfer Logic
;; ============================================================================

(defn create-transfer
  "Create energy transfer record."
  [source-id dest-id amount & [{:keys [efficiency] :or {efficiency 1.0}}]]
  (->EnergyTransfer source-id dest-id (double amount) (double (max 0.0 (min 1.0 efficiency))) (System/currentTimeMillis)))

(defn transfer-energy
  "Transfer energy from source to destination containers."
  [source dest amount efficiency]
  (let [efficiency (max 0.0 (min 1.0 efficiency))
        effective-amount (* amount efficiency)
        [source-after extracted] (extract-energy source amount)
        loss (- extracted effective-amount)
        [dest-after received] (receive-energy dest effective-amount)]
    {:source source-after :dest dest-after :transferred received :lost loss}))

;; ============================================================================
;; Energy Queries
;; ============================================================================

(defn get-current-energy [container] (:current container))
(defn get-max-energy [container] (:max-capacity container))
(defn get-energy-percent [container] (if (> (:max-capacity container) 0) (/ (:current container) (:max-capacity container)) 0.0))
(defn is-full? [container] (>= (:current container) (:max-capacity container)))
(defn is-empty? [container] (<= (:current container) 0.0))
(defn has-energy? [container & [minimum]] (>= (:current container) (or minimum 0.0)))

;; ============================================================================
;; Energy Balance
;; ============================================================================

(defn balance-containers
  "Balance energy across multiple containers (all or nothing)."
  [containers]
  (if (empty? containers)
    []
    (let [total-energy (reduce + 0.0 (map :current containers))
          total-capacity (reduce + 0.0 (map :max-capacity containers))
          per-container (/ total-energy (count containers))]
      (if (<= total-energy total-capacity)
        (mapv (fn [c] (set-energy c per-container)) containers)
        nil))))

(defn redistribute-energy
  "Redistribute energy from overfull to underfull containers."
  [containers]
  (let [total (reduce + 0.0 (map :current containers))
        num (count containers)
        target-per (/ total num)]
    (mapv (fn [c] (if (> (:current c) target-per) (set-energy c target-per) c)) containers)))

;; ============================================================================
;; Energy Serialization
;; ============================================================================

(defn container->map [container]
  {:current (:current container) :max-capacity (:max-capacity container) :transfer-rate (:transfer-rate container) :efficiency (:efficiency container) :last-updated (:last-updated container)})

(defn map->container [m]
  (->EnergyContainer (get m :current 0.0) (get m :max-capacity 1000.0) (get m :transfer-rate 0.0) (get m :efficiency 1.0) (get m :last-updated (System/currentTimeMillis))))

(defn transfer->map [transfer]
  {:source-id (:source-id transfer) :dest-id (:dest-id transfer) :amount (:amount transfer) :efficiency (:efficiency transfer) :timestamp (:timestamp transfer)})

;; ============================================================================
;; Energy Status
;; ============================================================================

(defn container-summary
  "Get human-readable summary of container."
  [container & [label]]
  (let [label (or label "Container")
        pct (* 100 (get-energy-percent container))]
    (format "%s: %.1f/%.1f energy (%.1f%%)" label (:current container) (:max-capacity container) pct)))