(ns cn.li.neoforge262.setup.shared-event-install
  "Install NeoForge-shared ports that need versioned Mod*/Java bridges."
  (:require [cn.li.neoforgebase.integration.events.entity-attributes :as entity-attr-events]
            [cn.li.neoforgebase.integration.events.loot :as loot-events]
            [cn.li.neoforgebase.setup.capability-wiring :as capability-wiring]
            [cn.li.neoforgebase.setup.capability-setup :as capability-setup])
  (:import [cn.li.neoforge262.entity ModEntities]
           [cn.li.neoforge262.loot LootInjectionHelper]
           [cn.li.neoforge262.capability CapabilityRegistry
            ForgeCapabilityHandler
            ForgeProvidedCapabilitySupport]
           [cn.li.neoforge262.network ClojureNetwork]))

(defn install!
  []
  (entity-attr-events/install-register-mob-attrs!
    (fn [event entity-type]
      (ModEntities/registerMobDefaultAttributes event entity-type)))
  (loot-events/install-add-item-injection!
    (fn [evt item-id weight quality min max]
      (LootInjectionHelper/addItemInjection evt item-id weight quality min max)))
  (capability-wiring/install-capability-bridge!
    {:get-block #(CapabilityRegistry/getBlock %)
     :register-block! (fn [k cap] (CapabilityRegistry/registerBlock k cap))
     :get-or-create-block! (fn [k typ] (CapabilityRegistry/getOrCreateBlock k typ))
     :block-cap-for-type #(ForgeProvidedCapabilitySupport/blockCapabilityForType %)
     :register-all! #(ForgeCapabilityHandler/registerAll %)})
  (capability-setup/install-register-network!
    (fn [mod-bus]
      (ClojureNetwork/register mod-bus)))
  nil)
