(ns cn.li.ac.block.imag-fusor.block
  "Imaginary Fusor block thin coordinator."
  (:require [cn.li.ac.block.imag-fusor.handlers :as fusor-handlers]
            [cn.li.ac.block.imag-fusor.logic :as fusor-logic]
            [cn.li.ac.block.imag-fusor.config :as fusor-config]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap])
  (:import [cn.li.acapi.wireless IWirelessReceiver]))

(defonce-guard imag-fusor-installed?)

(defn init-imag-fusor!
  []
  (machine-reg/init-machine!
    {:guard imag-fusor-installed?
     :log-label "Imaginary Fusor"
     :tiles [{:id "imag-fusor"
              :registry-name "imag_fusor"
              :blocks ["imag-fusor"]
              :tick-fn fusor-logic/fusor-tick-fn
              :read-nbt-fn fusor-logic/fusor-scripted-load-fn
              :write-nbt-fn fusor-logic/fusor-scripted-save-fn}]
     :tile-ids ["imag-fusor"]
     :containers {"imag-fusor" fusor-logic/fusor-container-fns}
     ;; Wireless receiver capability (original AcademyCraft TileImagFusor extends TileReceiverBase
     ;; which implements IWirelessReceiver), used by wireless energy network.
     :after #(do
               (when-not (cn.li.mcmod.platform.capability/get-capability-entry :wireless-receiver)
                 (cn.li.mcmod.platform.capability/declare-capability!
                   :wireless-receiver IWirelessReceiver
                   (fn [be _side] (impls/->WirelessReceiverImpl
                                    be
                                    (fn [state] (or (:max-energy state) fusor-config/max-energy))
                                    (fn [_state] fusor-config/receiver-bandwidth)))))
               (doseq [tile-id ["imag-fusor"]]
                 (tile-logic/register-tile-capability! tile-id :wireless-receiver)))
     :blocks [(bdsl/create-block-spec
                "imag-fusor"
                {:registry-name "imag_fusor"
                 :physical {:material :stone
                            :hardness 3.0
                            :resistance 6.0
                            :requires-tool true
                            :harvest-tool :pickaxe
                            :harvest-level 1
                            :sounds :metal}
                 ;; Original AcademyCraft emits light level 6 when working (getLightValue).
                 ;; Dynamic light emission (frame>0 → 6, idle → 0) requires forge-layer
                 ;; Block.getLightEmission override keyed on frame blockstate property.
                 :rendering {:model-parent "minecraft:block/cube_all"
                             :textures {:all (modid/asset-path "block" "imag_fusor")}
                             :flat-item-icon? true}
                 :block-state {:block-state-properties fusor-logic/fusor-block-state-properties}
                 :events {:on-right-click fusor-logic/open-fusor-gui!}})]
     :network-handler fusor-handlers/register-network-handlers!}))
