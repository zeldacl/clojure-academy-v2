(ns cn.li.neoforge262.setup.shared-event-install
  "Install NeoForge-shared event ports that need versioned Mod* bridges."
  (:require [cn.li.neoforgebase.integration.events.entity-attributes :as entity-attr-events])
  (:import [cn.li.neoforge262.entity ModEntities]))

(defn install!
  []
  (entity-attr-events/install-register-mob-attrs!
    (fn [event entity-type]
      (ModEntities/registerMobDefaultAttributes event entity-type))))
