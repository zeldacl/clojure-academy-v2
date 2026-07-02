(ns cn.li.mcmod.lifecycle
  "Content lifecycle coordination across platform adapters.

   All lifecycle state is stored in the single Framework atom
   under [:service :lifecycle]. Content modules register their
   init callbacks through these functions; platform adapters
   invoke the run-*! functions at the appropriate lifecycle phases.

   Replaced pattern:
     BEFORE: private delay singleton + ^:dynamic *lifecycle-runtime* fallback
     AFTER:  reads/writes (get-in @(fw/fw-atom) [:service :lifecycle])"
  (:require [cn.li.mcmod.framework :as fw]))

;; ============================================================================
;; Lifecycle state shape
;; ============================================================================

(defn- default-lifecycle-state
  "Return the initial lifecycle state map."
  []
  {:content-init-fn nil
   :runtime-content-activation-fn nil
   :datagen-metadata-init-fns []
   :client-init-fns []
   :post-spi-client-init-fns []})

;; ============================================================================
;; State access helpers (internal)
;; ============================================================================

(defn- lifecycle-state
  "Read current lifecycle state from Framework.
   Returns nil during AOT compilation when *framework* is nil."
  []
  (when-let [fw-atom fw/*framework*]
    (get-in @fw-atom [:service :lifecycle])))

(defn- update-lifecycle!
  "Atomically update lifecycle state within Framework.
   Initializes state if not yet present.
   No-op during AOT compilation when *framework* is nil."
  [f & args]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom
           (fn [fw-state]
             (update-in fw-state [:service :lifecycle]
                        (fn [current]
                          (apply f (or current (default-lifecycle-state)) args))))))
  nil)

;; ============================================================================
;; Test support
;; ============================================================================

(defn reset-lifecycle-state-for-test!
  "Reset lifecycle state for tests."
  []
  (swap! (fw/fw-atom) assoc-in [:service :lifecycle] (default-lifecycle-state))
  nil)

;; ============================================================================
;; Content Init
;; ============================================================================

(defn register-content-init!
  "Register content init function (fn [] ...).
   Called by content modules via ContentInitBootstrap SPI.
   The function will be executed by platform adapters via run-content-init!."
  [init-fn]
  (update-lifecycle! assoc :content-init-fn init-fn)
  nil)

(defn run-content-init!
  "Run registered content init function, if present."
  []
  (when-let [f (:content-init-fn (lifecycle-state))]
    (f)))

;; ============================================================================
;; Runtime Content Activation
;; ============================================================================

(defn register-runtime-content-activation!
  "Register runtime content activation function (fn [] ...).
   Content modules should register their runtime-loader activation
   through this hook so platform adapters do not reference content
   namespaces directly."
  [activate-fn]
  (update-lifecycle! assoc :runtime-content-activation-fn activate-fn)
  nil)

(defn run-runtime-content-activation!
  "Run registered runtime content activation function, if present."
  []
  (when-let [f (:runtime-content-activation-fn (lifecycle-state))]
    (f)))

;; ============================================================================
;; Datagen Metadata Init
;; ============================================================================

(defn register-datagen-metadata-init!
  "Register content-owned datagen metadata initialization (fn [] ...).
   Platform datagen entrypoints execute these hooks through mc-1.20.1
   shared setup utilities instead of referencing concrete content namespaces."
  [init-fn]
  (update-lifecycle! update :datagen-metadata-init-fns conj init-fn)
  nil)

(defn run-datagen-metadata-init!
  "Run all registered datagen metadata initialization hooks."
  []
  (doseq [f (:datagen-metadata-init-fns (lifecycle-state))]
    (f)))

;; ============================================================================
;; Client Init
;; ============================================================================

(defn register-client-init!
  "Register client-side init function. Called by content modules.
   The function will be executed by platform adapters during client setup."
  [init-fn]
  (update-lifecycle! update :client-init-fns conj init-fn)
  nil)

(defn run-client-init!
  "Run all registered client init functions."
  []
  (doseq [f (:client-init-fns (lifecycle-state))]
    (f)))

;; ============================================================================
;; Post-SPI Client Init
;; ============================================================================

(defn register-post-spi-client-init!
  "Register a post-SPI client init function (fn [] ...).
   These callbacks run after platform SPI implementations are installed
   but before KeyMapping/input registration."
  [init-fn]
  (update-lifecycle! update :post-spi-client-init-fns conj init-fn)
  nil)

(defn run-post-spi-client-init!
  "Run all registered post-SPI client init functions."
  []
  (doseq [f (:post-spi-client-init-fns (lifecycle-state))]
    (f)))
