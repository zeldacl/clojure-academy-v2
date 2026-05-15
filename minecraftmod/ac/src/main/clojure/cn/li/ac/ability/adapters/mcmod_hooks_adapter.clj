(ns cn.li.ac.ability.adapters.mcmod-hooks-adapter
  "AC ability runtime bindings for platform lifecycle hooks.

  Thin coordinator that composes extracted server/client hook maps."
  (:require [cn.li.ac.ability.adapters.client-effect-hooks :as client-effect-hooks]
            [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui-hooks]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard hooks-installed?)

(defn install-ability-runtime-hooks!
  "Install AC handlers for platform power runtime callbacks."
  []
  (with-init-guard hooks-installed?
    (server-hooks/install-store!)
    (server-hooks/register-lifecycle-subscriptions!)
    (power-runtime/register-power-runtime-hooks!
      (merge (server-hooks/runtime-server-hooks)
             (client-ui-hooks/runtime-client-ui-hooks)
             (client-effect-hooks/runtime-client-effect-hooks)))
    (log/info "AC ability runtime hooks installed"))
  nil)
