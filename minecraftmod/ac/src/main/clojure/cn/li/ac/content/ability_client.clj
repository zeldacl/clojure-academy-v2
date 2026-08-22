(ns cn.li.ac.content.ability-client
  "Client-side ability content bootstrap.

  All skill FX are declarative EDN, installed into vfx-core's runtime below.
  There is no per-skill FX namespace to discover or initialize.

  This namespace must ONLY be required from client-side code paths
  (e.g. platform client entry points), never from dedicated-server code."
  (:require [cn.li.ac.ability.service.combat-catalog :as combat-catalog]
            [cn.li.ac.client.effect-controller :as vfx]
            [cn.li.ac.client.combat-vfx-adapter :as combat-vfx]
            [cn.li.ac.ability.client.keybinds :as keybinds]
            [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]
            [cn.li.vfx.install :as vfx-install]))

(defn init-client-fx!
  "Ensure all client FX registrations have been loaded.
  Safe to call multiple times."
  []
  (install/framework-once! ::fx-initialized?
  (fn []
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
