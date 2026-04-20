(ns cn.li.mcmod.lifecycle
  "Content lifecycle coordination across platform adapters.")

(defonce content-init-fn
  ;; Atom storing the content init function to run after platform bootstrap.
  (atom nil))

(defn register-content-init!
  "Register content init function (fn [] ...). Called by shared game logic.

   The function will be executed by platform adapters via `run-content-init!`."
  [init-fn]
  (reset! content-init-fn init-fn)
  nil)

(defn run-content-init!
  "Run registered content init function, if present."
  []
  (when-let [f @content-init-fn]
    (f)))

(defonce runtime-content-activation-fn
  ;; Atom storing the runtime content activation function.
  (atom nil))

(defn register-runtime-content-activation!
  "Register runtime content activation function (fn [] ...).

  Content modules should register their runtime-loader activation through this
  hook so platform adapters do not reference content namespaces directly."
  [activate-fn]
  (reset! runtime-content-activation-fn activate-fn)
  nil)

(defn run-runtime-content-activation!
  "Run registered runtime content activation function, if present."
  []
  (when-let [f @runtime-content-activation-fn]
    (f)))

(defonce client-init-fns
  ;; Vector of client init functions to run during client setup.
  (atom []))

(defn register-client-init!
  "Register client-side init function. Called by content modules (ac).

  The function will be executed by platform adapters during client setup."
  [init-fn]
  (swap! client-init-fns conj init-fn)
  nil)

(defn run-client-init!
  "Run all registered client init functions."
  []
  (doseq [f @client-init-fns]
    (f)))

