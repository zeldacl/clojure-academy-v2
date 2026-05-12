(ns cn.li.forge1201.integration.events
  "Forge 1.20.1 event handler compatibility facade.

  Actual handlers are split by domain under cn.li.forge1201.integration.events.*"
  (:require [cn.li.forge1201.integration.events.interact :as interact]
            [cn.li.forge1201.integration.events.block :as block]
            [cn.li.forge1201.integration.events.world :as world]
            [cn.li.forge1201.integration.events.loot :as loot]))

(defn handle-right-click
  [event-data]
  (interact/handle-right-click event-data))

(defn handle-right-click-event
  [evt]
  (interact/handle-right-click-event evt))

(defn handle-left-click-block-event
  [evt]
  (interact/handle-left-click-block-event evt))

(defn handle-block-place
  [event-data]
  (block/handle-block-place event-data))

(defn handle-block-place-event
  [evt]
  (block/handle-block-place-event evt))

(defn handle-block-break-event
  [evt]
  (block/handle-block-break-event evt))

(defn handle-world-load
  [evt]
  (world/handle-world-load evt))

(defn handle-world-unload
  [evt]
  (world/handle-world-unload evt))

(defn handle-loot-table-load
  [evt]
  (loot/handle-loot-table-load evt))
