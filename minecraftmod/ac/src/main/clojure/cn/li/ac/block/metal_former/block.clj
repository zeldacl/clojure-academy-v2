(ns cn.li.ac.block.metal-former.block
  "Metal Former block - thin coordinator."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.metal-former.logic :as former-logic]
            [cn.li.ac.block.metal-former.handlers :as former-handlers])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard metal-former-installed?)

(defn init-metal-former!
  []
  (with-init-guard metal-former-installed?
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "metal-former"
        {:registry-name "metal_former"
         :impl :scripted
         :blocks ["metal-former"]
         :tick-fn former-logic/former-tick-fn
         :read-nbt-fn former-logic/former-scripted-load-fn
         :write-nbt-fn former-logic/former-scripted-save-fn}))
    (tile-logic/register-container! "metal-former" former-logic/former-container-fns)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "metal-former"
        {:registry-name "metal_former"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "metal_former_front")}
                     :flat-item-icon? true}
         :block-state {:block-state-properties former-logic/former-block-state-properties}
         :events {:on-right-click former-logic/open-former-gui!}}))
    (hooks/register-network-handler! former-handlers/register-network-handlers!)
    (log/info "Initialized Metal Former block")))

