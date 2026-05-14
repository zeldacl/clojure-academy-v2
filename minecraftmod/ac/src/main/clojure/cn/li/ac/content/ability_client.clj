(ns cn.li.ac.content.ability-client
  "Client-side ability content bootstrap.

  Requires discovered _fx.clj namespaces so they self-register their FX channels,
  level effects, and hand effects at load time.

  This namespace must ONLY be required from client-side code paths
  (e.g. platform client entry points), never from dedicated-server code."
  (:require [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard fx-initialized?)

(defn- require-discovered-fx! []
  (discovery/bootstrap-default-providers!)
  (doseq [ns-sym (discovery/discovered-fx-namespaces)]
    (require ns-sym)))

(defonce ^:private discovered-fx-required?
  (delay
    (require-discovered-fx!)
    true))

(defn ensure-discovered-fx-required!
  "Preserve historical load semantics for callers that only require this namespace."
  []
  @discovered-fx-required?)

(ensure-discovered-fx-required!)

(defn init-client-fx!
  "Ensure all client FX registrations have been loaded.
  Safe to call multiple times."
  []
  (with-init-guard fx-initialized?
    (ensure-discovered-fx-required!)
    (log/info "Ability client FX content initialized")))
