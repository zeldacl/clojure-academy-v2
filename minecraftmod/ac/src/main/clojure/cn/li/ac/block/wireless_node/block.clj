(ns cn.li.ac.block.wireless-node.block
  "Wireless Node block - thin coordinator."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.handlers :as node-handlers]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless IWirelessNode]
           [cn.li.mcmod.energy IEnergyCapable]))

(defn get-all-wireless-nodes []
  [(bdsl/get-block "wireless-node-basic")
   (bdsl/get-block "wireless-node-standard")
   (bdsl/get-block "wireless-node-advanced")])

(defonce-guard wireless-node-installed?)

(defn init-wireless-nodes! []
  (with-init-guard wireless-node-installed?
    (node-logic/ensure-node-slot-schema!)
    (msg-registry/register-block-messages!
      :node
      [:get-status :change-name :change-password :list-networks :connect :disconnect])
    (tile-logic/register-tile-kind!
      :wireless-node
      {:tick-fn node-logic/node-scripted-tick-fn
       :read-nbt-fn node-logic/node-scripted-load-fn
       :write-nbt-fn node-logic/node-scripted-save-fn})
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-basic"
        {:registry-name "node_basic"
         :impl :scripted
         :blocks ["wireless-node-basic"]
         :tile-kind :wireless-node}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-standard"
        {:registry-name "node_standard"
         :impl :scripted
         :blocks ["wireless-node-standard"]
         :tile-kind :wireless-node}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wireless-node-advanced"
        {:registry-name "node_advanced"
         :impl :scripted
         :blocks ["wireless-node-advanced"]
         :tile-kind :wireless-node}))
    (platform-cap/declare-capability! :wireless-node IWirelessNode
      (fn [be _side] (node-logic/->WirelessNodeImpl be)))
    (platform-cap/declare-capability! :wireless-energy IEnergyCapable
      (fn [be _side] (node-logic/->ClojureEnergyImpl be)))
    (doseq [tile-id ["wireless-node-basic" "wireless-node-standard" "wireless-node-advanced"]]
      (tile-logic/register-tile-capability! tile-id :wireless-node)
      (tile-logic/register-tile-capability! tile-id :wireless-energy)
      (tile-logic/register-container! tile-id node-logic/node-container-fns))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-basic"
        {:registry-name "node_basic"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties node-logic/block-state-properties}
         :events {:on-right-click (node-logic/handle-node-right-click :basic)
                  :on-place (node-logic/handle-node-place :basic)
                  :on-break (node-logic/handle-node-break :basic)}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-standard"
        {:registry-name "node_standard"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties node-logic/block-state-properties}
         :events {:on-right-click (node-logic/handle-node-right-click :standard)
                  :on-place (node-logic/handle-node-place :standard)
                  :on-break (node-logic/handle-node-break :standard)}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "wireless-node-advanced"
        {:registry-name "node_advanced"
         :physical {:material :metal
                    :hardness 2.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"}
         :block-state {:block-state-properties node-logic/block-state-properties}
         :events {:on-right-click (node-logic/handle-node-right-click :advanced)
                  :on-place (node-logic/handle-node-place :advanced)
                  :on-break (node-logic/handle-node-break :advanced)}}))
    (hooks/register-network-handler! node-handlers/register-network-handlers!)
    (log/info "Initialized Wireless Nodes (thin coordinator):")
    (doseq [[tier cfg] (node-logic/node-types)]
      (log/info "  -" (name tier) ": max-energy=" (:max-energy cfg)))
    (log/info "  - Capabilities :wireless-node + :wireless-energy registered")))
