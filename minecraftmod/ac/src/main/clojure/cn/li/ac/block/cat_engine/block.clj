(ns cn.li.ac.block.cat-engine.block
  "Cat Engine block - thin coordinator."
  (:require [cn.li.ac.block.cat-engine.logic :as cat-logic]
            [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard cat-engine-installed?)

(defn init-cat-engine!
  []
  (machine-reg/init-machine!
    {:guard cat-engine-installed?
     :log-label "Cat Engine"
     :tiles [{:id "cat-engine"
              :registry-name "cat_engine"
              :blocks ["cat-engine"]
              :tick-fn cat-logic/cat-tick-fn
              :read-nbt-fn cat-logic/cat-scripted-load-fn
              :write-nbt-fn cat-logic/cat-scripted-save-fn}]
     :tile-ids ["cat-engine"]
     :capabilities [{:key :wireless-generator
                     :interface IWirelessGenerator
                     :factory (fn [be _side] (impls/->WirelessGeneratorImpl be))}]
     :blocks [(bdsl/create-block-spec
                "cat-engine"
                {:registry-name "cat_engine"
                 :physical {:material :metal
                            :hardness 2.0
                            :resistance 6.0
                            :requires-tool true
                            :harvest-tool :pickaxe
                            :harvest-level 1
                            :sounds :metal}
                 :rendering {:model-parent "minecraft:block/cube_all"
                             :textures {:all (modid/asset-path "block" "cat_engine")}
                             :flat-item-icon? true
                             :light-level 0
                             :render-shape :invisible}
                 :events {:on-right-click cat-logic/cat-right-click!}})]
     :client-renderer 'cn.li.ac.block.cat-engine.render/init!}))
