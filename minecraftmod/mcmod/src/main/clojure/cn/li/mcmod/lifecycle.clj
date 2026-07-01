(ns cn.li.mcmod.lifecycle
  "Content lifecycle coordination across platform adapters.")

(defn- default-lifecycle-runtime-state []
  {:content-init-fn nil
   :runtime-content-activation-fn nil
   :datagen-metadata-init-fns []
   :client-init-fns []
   :post-spi-client-init-fns []})

(defn create-lifecycle-runtime
  ([] (create-lifecycle-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.lifecycle/runtime ::lifecycle-runtime
    :state* (or state* (atom (default-lifecycle-runtime-state)))}))

(def ^:dynamic *lifecycle-runtime* nil)

(defonce ^:private installed-lifecycle-runtime
  (create-lifecycle-runtime))

(defn- lifecycle-state-atom []
  (:state* (or *lifecycle-runtime* installed-lifecycle-runtime)))

(defn- lifecycle-state-snapshot []
  @(lifecycle-state-atom))

(defn reset-lifecycle-state-for-test!
  "Reset lifecycle runtime state. Intended for tests."
  []
  (reset! (lifecycle-state-atom) (default-lifecycle-runtime-state))
  nil)

(defn register-content-init!
  "Register content init function (fn [] ...). Called by shared game logic.

   The function will be executed by platform adapters via `run-content-init!`."
  [init-fn]
  (swap! (lifecycle-state-atom) assoc :content-init-fn init-fn)
  nil)

(defn run-content-init!
  "Run registered content init function, if present."
  []
  (when-let [f (:content-init-fn (lifecycle-state-snapshot))]
    (f)))

(defn register-runtime-content-activation!
  "Register runtime content activation function (fn [] ...).

  Content modules should register their runtime-loader activation through this
  hook so platform adapters do not reference content namespaces directly."
  [activate-fn]
  (swap! (lifecycle-state-atom) assoc :runtime-content-activation-fn activate-fn)
  nil)

(defn run-runtime-content-activation!
  "Run registered runtime content activation function, if present."
  []
  (when-let [f (:runtime-content-activation-fn (lifecycle-state-snapshot))]
    (f)))

(defn register-datagen-metadata-init!
  "Register content-owned datagen metadata initialization (fn [] ...).

  Platform datagen entrypoints execute these hooks through mc-1.20.1 shared
  setup utilities instead of referencing concrete content namespaces."
  [init-fn]
  (swap! (lifecycle-state-atom) update :datagen-metadata-init-fns conj init-fn)
  nil)

(defn run-datagen-metadata-init!
  "Run all registered datagen metadata initialization hooks."
  []
  (doseq [f (:datagen-metadata-init-fns (lifecycle-state-snapshot))]
    (f)))

(defn register-client-init!
  "Register client-side init function. Called by content modules.

  The function will be executed by platform adapters during client setup."
  [init-fn]
  (swap! (lifecycle-state-atom) update :client-init-fns conj init-fn)
  nil)

(defn run-client-init!
  "Run all registered client init functions."
  []
  (doseq [f (:client-init-fns (lifecycle-state-snapshot))]
    (f)))

(defn register-post-spi-client-init!
  "Register a post-SPI client init function (fn [] ...).

  These callbacks run after platform SPI implementations are installed
  but before KeyMapping/input registration. Content modules that need
  SPI providers ready should use this slot instead of :client-init-fns.

  Called by platform adapters via `run-post-spi-client-init!`."
  [init-fn]
  (swap! (lifecycle-state-atom) update :post-spi-client-init-fns conj init-fn)
  nil)

(defn run-post-spi-client-init!
  "Run all registered post-SPI client init functions."
  []
  (doseq [f (:post-spi-client-init-fns (lifecycle-state-snapshot))]
    (f)))

