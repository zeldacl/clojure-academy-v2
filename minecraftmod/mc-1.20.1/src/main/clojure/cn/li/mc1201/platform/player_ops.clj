(ns cn.li.mc1201.platform.player-ops)

(defprotocol PlayerOps
  (player-level [this player])
  (player-container-menu [this player])
  (count-player-item-by-id [this player item-id])
  (consume-player-item-by-id! [this player item-id amount])
  (give-player-item-stack! [this player stack])
  (spawn-entity-by-id! [this player entity-id speed])
  (raytrace-block [this player reach fluid-source-only?]))
