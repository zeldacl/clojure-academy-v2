(ns cn.li.mc1201.platform.class-access)

(defprotocol ClassAccess
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
  (scripted-be-class [this]))
