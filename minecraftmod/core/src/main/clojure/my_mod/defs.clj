(ns my-mod.defs
  (:require [my-mod.config.modid :as modid]))

(def mod-id modid/MOD-ID)
;; Legacy demo IDs - deprecated, kept for backward compatibility
(def demo-item-id "demo_item")
(def demo-block-id "demo_block")
