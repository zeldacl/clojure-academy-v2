(ns cn.li.ac.block.imag-fusor.block
  "Imaginary Fusor block thin coordinator."
  (:require [cn.li.ac.block.imag-fusor.handlers :as fusor-handlers]
            [cn.li.ac.block.imag-fusor.logic :as fusor-logic]
            [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]))

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
     :containers {"imag-fusor" fusor-logic/fusor-container-fns}
     :blocks [(bdsl/create-block-spec
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
                 :events {:on-right-click fusor-logic/open-fusor-gui!}})]
     :network-handler fusor-handlers/register-network-handlers!}))
