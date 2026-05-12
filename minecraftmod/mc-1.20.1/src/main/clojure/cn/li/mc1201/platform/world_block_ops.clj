(ns cn.li.mc1201.platform.world-block-ops)

(defprotocol WorldBlockOps
  (world-place-block-by-id [this level block-id pos flags]))
