(ns cn.li.ac.block.imag-fusor.block
  "Imaginary Fusor block thin coordinator."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.block.imag-fusor.logic :as fusor-logic]
            [cn.li.ac.block.imag-fusor.handlers :as fusor-handlers]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]))


(defonce-guard imag-fusor-installed?)

(defn init-imag-fusor!
  []
  (with-init-guard imag-fusor-installed?
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "imag-fusor"
        {:registry-name "imag_fusor"
         :impl :scripted
         :blocks ["imag-fusor"]
         :tick-fn fusor-logic/fusor-tick-fn
         :read-nbt-fn fusor-logic/fusor-scripted-load-fn
         :write-nbt-fn fusor-logic/fusor-scripted-save-fn}))
    
    (tile-logic/register-container! "imag-fusor" fusor-logic/fusor-container-fns)
    
    (bdsl/register-block!
      (bdsl/create-block-spec
        "imag-fusor"
        {:registry-name "imag_fusor"
         :physical {:material :metal
                    :hardness 3.5
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "imag_fusor")}
                     :flat-item-icon? true}
         :block-state {:block-state-properties fusor-logic/fusor-block-state-properties}
         :events {:on-right-click fusor-logic/open-fusor-gui!}}))
    
    (hooks/register-network-handler! fusor-handlers/register-network-handlers!)
    (log/info "Initialized Imaginary Fusor block")))
