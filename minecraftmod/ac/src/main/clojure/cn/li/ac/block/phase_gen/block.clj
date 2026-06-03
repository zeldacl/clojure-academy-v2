(ns cn.li.ac.block.phase-gen.block
  "Phase Generator block - thin coordinator."
  (:require [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.phase-gen.handlers :as phase-handlers]
            [cn.li.ac.block.phase-gen.logic :as phase-logic]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard phase-gen-installed?)

(defn init-phase-gen!
  []
  (machine-reg/init-machine!
    {:guard phase-gen-installed?
     :log-label "Phase Generator"
     :tiles [{:id "phase-gen"
              :registry-name "phase_gen"
              :blocks ["phase-gen"]
              :tick-fn phase-logic/phase-tick-fn
              :read-nbt-fn phase-logic/phase-scripted-load-fn
              :write-nbt-fn phase-logic/phase-scripted-save-fn}]
     :tile-ids ["phase-gen"]
     :containers {"phase-gen" phase-logic/phase-container-fns}
     :capabilities [{:key :phase-generator
                     :interface IWirelessGenerator
                     :factory (fn [be _side] (impls/->WirelessGeneratorImpl be))}]
     :blocks [(bdsl/create-block-spec
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
                 :events {:on-right-click phase-logic/open-phase-gen-gui!}})]
     :network-handler phase-handlers/register-network-handlers!
     :client-renderer 'cn.li.ac.block.phase-gen.render/init!}))
