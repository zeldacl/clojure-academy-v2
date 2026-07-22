(ns cn.li.mc1201.runtime.adapter.block-manipulation
  "Shared IBlockManipulation adapter factory.

  Platform namespaces provide the server lookup and break guard. Shared
  Minecraft-version-specific code owns the protocol implementation and delegates
  the actual block operations to block-manipulation-core."
  (:require [cn.li.mc1201.runtime.block-manipulation-core :as core]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn create-block-manipulation
  [server-fn break-guard-fn]
  {:break-block! (fn
                   ([player-id world-id x y z drop?]
                    (core/break-block! (server-fn) player-id world-id x y z drop? break-guard-fn))
                   ([player-id world-id x y z drop? fortune-level]
                    (core/break-block! (server-fn) player-id world-id x y z drop? fortune-level break-guard-fn)))
   :set-block! (fn [world-id x y z block-id]
                 (core/set-block! (server-fn) world-id x y z block-id))
   :get-block (fn [world-id x y z]
                (core/get-block (server-fn) world-id x y z))
   :get-block-hardness (fn [world-id x y z]
                         (core/get-block-hardness (server-fn) world-id x y z))
   :can-break-block? (fn [player-id world-id x y z]
                       (boolean (core/can-break-block? (server-fn) player-id world-id x y z break-guard-fn)))
   :find-blocks-in-line (fn [world-id x1 y1 z1 dx dy dz max-distance]
                          (core/find-blocks-in-line (server-fn) world-id x1 y1 z1 dx dy dz max-distance))
   :liquid-block? (fn [world-id x y z]
                    (boolean (core/liquid-block? (server-fn) world-id x y z)))
   :farmland-block? (fn [world-id x y z]
                      (boolean (core/farmland-block? (server-fn) world-id x y z)))})

(defn install-block-manipulation!
  [block-manipulation label]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/install-adapter! fw-atom :block-manipulation block-manipulation))
  nil)
