(ns cn.li.ac.wireless.node.blockstate
  "Node blockstate domain entry namespace.

  During migration this namespace delegates to legacy blockstate namespace."
  (:require [cn.li.ac.block.wireless-node.blockstate :as legacy]))

(def get-all-node-definitions legacy/get-all-node-definitions)
(def get-node-blockstate-definition legacy/get-node-blockstate-definition)
(def get-node-model-texture-config legacy/get-node-model-texture-config)
(def is-node-block? legacy/is-node-block?)
(def get-node-item-model-id legacy/get-node-item-model-id)