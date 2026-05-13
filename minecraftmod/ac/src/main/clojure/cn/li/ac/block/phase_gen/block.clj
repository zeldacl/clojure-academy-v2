(ns cn.li.ac.block.phase-gen.block
  "Phase Generator block - thin coordinator."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.phase-gen.logic :as phase-logic]
            [cn.li.ac.block.phase-gen.handlers :as phase-handlers])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard phase-gen-installed?)

(defn init-phase-gen!
  []
  (with-init-guard phase-gen-installed?
    (msg-registry/register-block-messages! :phase-gen [:get-status])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :impl :scripted
         :blocks ["phase-gen"]
         :tick-fn phase-logic/phase-tick-fn
         :read-nbt-fn phase-logic/phase-scripted-load-fn
         :write-nbt-fn phase-logic/phase-scripted-save-fn}))
    (tile-logic/register-container! "phase-gen" phase-logic/phase-container-fns)
    (platform-cap/declare-capability! :phase-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "phase-gen" :phase-generator)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/block"
                     :textures {:all (modid/asset-path "block" "phase_gen")}
                     :flat-item-icon? true}
         :events {:on-right-click phase-logic/open-phase-gen-gui!}}))
    (hooks/register-network-handler! phase-handlers/register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.phase-gen.render/init!)
    (log/info "Initialized Phase Generator block")))
