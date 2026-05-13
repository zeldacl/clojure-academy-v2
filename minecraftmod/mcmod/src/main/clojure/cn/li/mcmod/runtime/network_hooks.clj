(ns cn.li.mcmod.runtime.network-hooks
  "Network/context runtime hook surface (compat wrapper)."
  (:require [cn.li.mcmod.runtime.hooks.network :as network-hooks]))

(def register-network-handlers! network-hooks/register-network-handlers!)
(def register-context-route-fns! network-hooks/register-context-route-fns!)
(def register-context-send-fns! network-hooks/register-context-send-fns!)
(def get-context-player-uuid network-hooks/get-context-player-uuid)
(def on-runtime-item-action! network-hooks/on-runtime-item-action!)
(def build-item-use-plan network-hooks/build-item-use-plan)
