(ns cn.li.mc1201.platform-adapter)

(defprotocol PlatformAdapter
  (entity-class [this])
  (player-class [this])
  (server-player-class [this])
  (local-player-class [this])
  (inventory-class [this])
  (menu-class [this])
  (item-stack-class [this])
  (item-class [this])
  (block-state-class [this])
  (level-class [this])
  (scripted-be-class [this])

  (item-registry-name [this item])
  (block-registry-name [this block])

  (player-level [this player])
  (player-container-menu [this player])
  (inventory-owner [this inventory])
  (menu-container-id [this menu])

  (count-player-item-by-id [this player item-id])
  (consume-player-item-by-id! [this player item-id amount])
  (give-player-item-stack! [this player stack])
  (spawn-entity-by-id! [this player entity-id speed])
  (raytrace-block [this player reach fluid-source-only?])

  (item-stack-of [this nbt])
  (create-item-stack-by-id [this item-id count])
  (item-stack-empty? [this stack])

  (world-place-block-by-id [this level block-id pos flags]))
