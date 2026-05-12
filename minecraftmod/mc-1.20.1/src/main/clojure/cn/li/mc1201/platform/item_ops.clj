(ns cn.li.mc1201.platform.item-ops)

(defprotocol ItemOps
  (item-registry-name [this item])
  (block-registry-name [this block])
  (item-stack-of [this nbt])
  (create-item-stack-by-id [this item-id count])
  (item-stack-empty? [this stack]))
