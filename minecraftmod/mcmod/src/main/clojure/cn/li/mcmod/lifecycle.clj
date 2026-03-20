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

