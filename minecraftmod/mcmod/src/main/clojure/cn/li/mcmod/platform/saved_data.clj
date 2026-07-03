(ns cn.li.mcmod.platform.saved-data
  "Platform-neutral SavedData SPI via Framework function map.

   Providers register as maps with keys :data-id :save-fn :load-fn.
   Platform ops stored at [:platform :saved-data-ops].
   Provider registry stored at [:service :saved-data-providers]."
  (:require [cn.li.mcmod.framework :as fw]))

;; ============================================================================
;; Platform ops — installed by forge/fabric
;; ============================================================================

(defn install-saved-data-ops!
  "Install platform SavedData operations map.
   ops-map keys: :nbt-ops :get-or-create :mark-dirty! :read-nbt :write-nbt"
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :saved-data-ops] ops-map)) nil)

(defn saved-data-ops-available?
  []
  (some? (get-in @(fw/fw-atom) [:platform :saved-data-ops])))

;; ============================================================================
;; Provider registry — stored in Framework, no volatile!
;; ============================================================================

(defn register-saved-data-provider!
  "Register a saved-data provider map: {:data-id string, :save-fn fn, :load-fn fn}."
  [provider]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in [:service :saved-data-providers] (fn [v] (conj (or v []) provider))))
  nil)

(defn list-saved-data-providers
  []
  (get-in @(fw/fw-atom) [:service :saved-data-providers] []))

(defn reset-saved-data-providers-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:service :saved-data-providers] [])) nil)

;; ============================================================================
;; Lifecycle dispatch — called by platform event handlers
;; ============================================================================

(defn dispatch-world-load!
  "Called by platform when a server world loads."
  [world]
  (when-let [ops (get-in @(fw/fw-atom) [:platform :saved-data-ops])]
    (doseq [provider (list-saved-data-providers)]
      (try
        (let [data-id (:data-id provider)
              payload (when-let [reader (:read-nbt ops)]
                        (reader world data-id))]
          ((:load-fn provider) world payload))
        (catch Throwable t
          (throw (ex-info (str "SavedData load failed for " (:data-id provider))
                          {:provider provider :world world} t)))))))

(defn dispatch-world-save!
  "Called by platform before a server world saves."
  [world]
  (when-let [ops (get-in @(fw/fw-atom) [:platform :saved-data-ops])]
    (doseq [provider (list-saved-data-providers)]
      (try
        (when-let [payload ((:save-fn provider) world)]
          (let [data-id (:data-id provider)]
            ((:write-nbt ops) world data-id payload)
            ((:mark-dirty! ops) world data-id)))
        (catch Throwable t
          (throw (ex-info (str "SavedData save failed for " (:data-id provider))
                          {:provider provider :world world} t)))))))
