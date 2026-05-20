(ns cn.li.ac.content.ability-client
  "Client-side ability content bootstrap.

  Explicitly initializes discovered _fx.clj namespaces so they register their FX
  channels, level effects, and hand effects only during client FX init.

  This namespace must ONLY be required from client-side code paths
  (e.g. platform client entry points), never from dedicated-server code."
  (:require [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard fx-initialized?)

(defn- init-fx-namespace! [ns-sym]
  (require ns-sym)
  (when-let [init-var (ns-resolve ns-sym 'init!)]
    (when (bound? init-var)
      (init-var))))

(defn- init-discovered-fx! []
  (doseq [ns-sym (discovery/discovered-fx-namespaces)]
    (init-fx-namespace! ns-sym)))

(defn init-client-fx!
  "Ensure all client FX registrations have been loaded.
  Safe to call multiple times."
  []
  (with-init-guard fx-initialized?
    (init-discovered-fx!)
    (log/info "Ability client FX content initialized")))
