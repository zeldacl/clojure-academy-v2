(ns cn.li.mc1201.platform.menu-inventory-ops)

(defprotocol MenuInventoryOps
  (inventory-owner [this inventory])
  (menu-container-id [this menu]))
