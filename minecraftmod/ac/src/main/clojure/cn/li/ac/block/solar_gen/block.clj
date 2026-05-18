(ns cn.li.ac.block.solar-gen.block
  "Solar Generator block - thin coordinator.
  Business logic: solar-gen.logic
  Network handlers: solar-gen.handlers"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.solar-gen.logic :as solar-logic]
            [cn.li.ac.block.solar-gen.handlers :as solar-handlers])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard solar-gen-installed?)

(defn init-solar-gen!
  []
  (with-init-guard solar-gen-installed?
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "solar-gen"
        {:registry-name "solar_gen"
         :impl :scripted
         :blocks ["solar-gen"]
         :tick-fn solar-logic/solar-tick-fn
         :read-nbt-fn solar-logic/solar-read-nbt-fn
         :write-nbt-fn solar-logic/solar-write-nbt-fn}))
    (platform-cap/declare-capability! :wireless-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "solar-gen" :wireless-generator)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "solar-gen"
        {:registry-name "solar_gen"
         :physical {:material :stone
                    :hardness 1.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "solar_gen")}
                     :flat-item-icon? true}
         :events {:on-right-click solar-logic/open-solar-gui!}}))
    (hooks/register-network-handler! solar-handlers/register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.solar-gen.render/init!)
    (log/info "Initialized Solar Generator block")))