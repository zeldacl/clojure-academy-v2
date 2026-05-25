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

(def ^:private sync-descriptors
  [{:id :ac/sync-runtime
    :content-id "ac"
    :message-key :sync-runtime
    :payload-key :ability-data
    :order 0}
   {:id :ac/sync-resource
    :content-id "ac"
    :message-key :sync-resource
    :payload-key :resource-data
    :order 10}
   {:id :ac/sync-cooldown
    :content-id "ac"
    :message-key :sync-cooldown
    :payload-key :cooldown-data
    :order 20}
   {:id :ac/sync-preset
    :content-id "ac"
    :message-key :sync-preset
    :payload-key :preset-data
    :order 30}])

(def ^:private player-persistence-descriptors
  [{:id :ac/player-runtime-state
    :content-id "ac"
    :kind :runtime-state
    :format :edn
    :nbt-key "ac_ability_state"
    :order 0}
   {:id :ac/saved-locations
    :content-id "ac"
    :kind :content-owned-compound
    :format :compound-tag
    :host-key :named-world-position-store
    :nbt-key "SavedLocations"
    :clone? true
    :order 10}])

(defn- install-sync-descriptors!
  []
  (doseq [descriptor sync-descriptors]
    (runtime-hooks/register-sync-descriptor! descriptor))
  nil)

(defn- install-player-persistence-descriptors!
  []
  (doseq [descriptor player-persistence-descriptors]
    (runtime-hooks/register-player-persistence-descriptor! descriptor))
  nil)

(defn install-runtime-hooks!
  "Register AC runtime hooks exactly once.

  This bridges loader lifecycle/network code to the AC ability implementation;
  without it, mcmod.hooks.core stays on its no-op defaults."
  []
  (with-init-guard runtime-hooks-installed?
    (server-hooks/install-store!)
    (server-hooks/register-lifecycle-subscriptions!)
    (client-keybinds/install-default-handlers!)
    (install-sync-descriptors!)
    (install-player-persistence-descriptors!)
    (client-ui/install-client-input-descriptors!)
    (runtime-hooks/register-power-runtime-hooks!
      (merge (server-hooks/runtime-server-hooks)
             (client-ui/runtime-client-ui-hooks)
             (client-effects/runtime-client-effect-hooks)))
    (log/info "AC ability runtime hooks installed")))