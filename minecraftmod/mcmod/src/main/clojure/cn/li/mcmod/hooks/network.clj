(ns cn.li.mcmod.hooks.network
  "Runtime network/context/item-action hook surface (delegates to hooks-core during migration)."
  (:require [cn.li.mcmod.hooks.core :as hooks-core]))

(def register-network-handlers! hooks-core/register-network-handlers!)
(def register-context-route-fns! hooks-core/register-context-route-fns!)
(def register-context-send-fns! hooks-core/register-context-send-fns!)
(def get-context-player-uuid hooks-core/get-context-player-uuid)
(def on-runtime-item-action! hooks-core/on-runtime-item-action!)
(def build-item-use-plan hooks-core/build-item-use-plan)
