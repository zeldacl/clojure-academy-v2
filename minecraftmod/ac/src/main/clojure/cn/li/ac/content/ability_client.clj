(ns cn.li.ac.content.ability-client
  "Client-side ability content bootstrap.

  Explicitly initializes discovered _fx.clj namespaces so they register their FX
  channels, level effects, and hand effects only during client FX init.

  This namespace must ONLY be required from client-side code paths
  (e.g. platform client entry points), never from dedicated-server code."
  (:require [cn.li.ac.ability.discovery :as discovery]
            [cn.li.ac.discovery.core :as discovery-core]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.client.effect-controller :as vfx]
            [cn.li.ac.client.combat-vfx-adapter :as combat-vfx]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.vfx.install :as vfx-install]))

(defn- init-fx-namespace! [ns-sym]
  (let [ns-sym (discovery-core/ns-symbol ns-sym)]
    (require ns-sym)
    (when-let [init-var (ns-resolve ns-sym 'init!)]
      (when (bound? init-var)
        (init-var)))))

(defn- init-discovered-fx! []
  (doseq [ns-sym (discovery/discovered-fx-namespaces)]
    (init-fx-namespace! ns-sym)))

(defn init-client-fx!
  "Ensure all client FX registrations have been loaded.
  Safe to call multiple times."
  []
  (install/framework-once! ::fx-initialized?
  (fn []
    (init-discovered-fx!)
    ;; Validate multimethod arities BEFORE freezing — catches the class of
    ;; bug where a defmethod with wrong arity silently corrupts the dispatch
    ;; table for ALL effects; declarative VFX are resolved by the shared catalog.
    (arc-beam/validate-fx-multimethods!)
    (keybinds/freeze-keybind-registries!)
    (vfx/warmup!)
    ;; Register every compiled EDN VFX effect as a real vfx-core descriptor
    ;; before the registry freezes -- until this call, an EDN ability's
    ;; :effect/vfx signals compiled fine but had no registered effect-id to
    ;; land on, so they were silently dropped (see effect_controller.clj's
    ;; unmapped-signal-count*). combat-catalog/initialize! (ac/core/init.clj)
    ;; must already have run by the time client init reaches here.
    (vfx-install/install-catalog! (vfx/runtime) (:vfx (combat-catalog/catalog)))
    (vfx/freeze!)
    (combat-vfx/install-dispatch! vfx/dispatch-signal!)
    (log/info "Ability client FX content initialized"))))

(defn reset-client-fx-for-test!
  "Test-only: clear the client-FX install guard so init-client-fx! can rerun
   within the same Framework lifetime."
  []
  (install/reset-framework-once-flag-for-test! ::fx-initialized?)
  nil)
