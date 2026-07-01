(ns cn.li.mcmod.platform.saved-data
  "Platform-neutral SavedData SPI.

  Content modules implement ISavedDataProvider to define per-world persisted
  state. Platform adapters (forge/fabric) install an implementation of the
  saved-data-ops map via `install-saved-data-ops!`.

  Pattern follows the existing platform SPI convention in
  `cn.li.mcmod.platform.runtime`.")

;; ============================================================================
;; SPI Protocol — implemented by content modules
;; ============================================================================

(defprotocol ISavedDataProvider
  "Per-world persisted state provider.

  Each content module implements this for data that must survive world
  save/load (e.g. wireless network topology)."
  (saved-data-id [this]
    "Return a unique string key for this data slot (e.g. \"ac_wireless\").")
  (save-world-data! [this world]
    "Return an opaque NBT payload for the current world state, or nil.")
  (load-world-data! [this world nbt-payload]
    "Restore state from a previously-saved NBT payload. Called on world load."))

;; ============================================================================
;; Platform ops — installed by forge/fabric
;; ============================================================================

(def ^:private ^:dynamic *saved-data-ops* nil)

(defn install-saved-data-ops!
  "Install platform SavedData operations.

  ops-map keys:
    :nbt-ops - INbtOps implementation for creating/reading NBT compounds
    :get-or-create (fn [world data-id]) -> SavedData wrapper
    :mark-dirty!  (fn [world data-id]) -> mark data as needing save"
  [ops-map label]
  (alter-var-root #'*saved-data-ops* (constantly ops-map))
  nil)

(defn saved-data-ops-available?
  "Return true if platform SavedData ops have been installed."
  []
  (some? *saved-data-ops*))

;; ============================================================================
;; Provider registry
;; ============================================================================

(let [providers (volatile! [])]
  (defn register-saved-data-provider!
    "Register an ISavedDataProvider during content init.
    Called once per provider."
    [provider]
    (vreset! providers (conj @providers provider))
    nil)

  (defn list-saved-data-providers
    "Return all registered ISavedDataProvider instances."
    []
    @providers)

  (defn reset-saved-data-providers-for-test!
    "Clear all registered providers. For tests only."
    []
    (vreset! providers [])
    nil))

;; ============================================================================
;; Lifecycle dispatch — called by platform event handlers
;; ============================================================================

(defn dispatch-world-load!
  "Called by platform when a server world loads.
  Iterates all registered providers and calls load-world-data! for each."
  [world]
  (when-let [ops *saved-data-ops*]
    (let [nbt-ops (:nbt-ops ops)]
      (doseq [provider (list-saved-data-providers)]
        (try
          (let [data-id (saved-data-id provider)
                payload (when nbt-ops
                          ;; Platform provides a NBT reader for the data-id
                          ((:read-nbt ops) world data-id))]
            (load-world-data! provider world payload))
          (catch Throwable t
            (throw (ex-info (str "SavedData load failed for " (saved-data-id provider))
                            {:provider provider :world world} t))))))))

(defn dispatch-world-save!
  "Called by platform before a server world saves.
  Iterates all registered providers and collects save data."
  [world]
  (when-let [ops *saved-data-ops*]
    (doseq [provider (list-saved-data-providers)]
      (try
        (when-let [payload (save-world-data! provider world)]
          (let [data-id (saved-data-id provider)]
            ((:write-nbt ops) world data-id payload)
            ((:mark-dirty! ops) world data-id)))
        (catch Throwable t
          (throw (ex-info (str "SavedData save failed for " (saved-data-id provider))
                          {:provider provider :world world} t)))))))
