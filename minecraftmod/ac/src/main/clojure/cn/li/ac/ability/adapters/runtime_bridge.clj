(ns cn.li.ac.ability.adapters.runtime-bridge
  "Installs AC ability runtime hooks into the platform-neutral mcmod bridge."
  (:require [cn.li.ac.ability.adapters.client-effect-hooks :as client-effects]
            [cn.li.ac.ability.adapters.client-ui-hooks :as client-ui]
            [cn.li.ac.ability.adapters.server-hooks :as server-hooks]
            [cn.li.ac.ability.client.keybinds :as client-keybinds]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

(defonce-guard runtime-hooks-installed?)

(defn install-runtime-hooks!
  "Register AC runtime hooks exactly once.

  This bridges loader lifecycle/network code to the AC ability implementation;
  without it, mcmod.hooks.core stays on its no-op defaults."
  []
  (with-init-guard runtime-hooks-installed?
    (server-hooks/install-store!)
    (server-hooks/register-lifecycle-subscriptions!)
    (client-keybinds/install-default-handlers!)
    (runtime-hooks/register-power-runtime-hooks!
      (merge (server-hooks/runtime-server-hooks)
             (client-ui/runtime-client-ui-hooks)
             (client-effects/runtime-client-effect-hooks)))
    (log/info "AC ability runtime hooks installed")))