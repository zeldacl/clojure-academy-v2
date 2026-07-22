(ns cn.li.ac.block.wind-gen.block
  "Wind Generator blocks thin coordinator."
  (:require [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.wind-gen.handlers :as wind-handlers]
            [cn.li.ac.block.wind-gen.logic :as wind-logic]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.block.dsl :as bdsl])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defn- register-wind-multiblocks! []
  (bdsl/defmultiblock 'wind-gen-main
    :multi-block {:positions [[0 0 0] [0 0 -1] [0 0 1]]
                  :rotation-center [0.5 0.0 0.4]
                  :tesr-use-raw-rotation-center? true}
    :common {:physical {:material :metal
                       :hardness 3.0
                       :resistance 6.0
                       :requires-tool true
                       :harvest-tool :pickaxe
                       :harvest-level 1
                       :sounds :metal}}
    :controller {:registry-name "wind_gen_main"
                 :rendering {:flat-item-icon? true
             :render-shape :invisible
                             :textures {:all (modid/asset-path "block" "windgen_main")}}
                 :events {:on-right-click wind-logic/open-wind-main-gui!
                          :on-place wind-logic/on-wind-main-placed!}}
    :part {:registry-name "wind_gen_main_part"
          :rendering {:model-parent "minecraft:block/cube_all"
            :render-shape :invisible
                       :textures {:all (modid/asset-path "block" "windgen_main")}}
           :events {:on-right-click wind-logic/open-wind-main-gui!}})

  (bdsl/defmultiblock 'wind-gen-base
    :multi-block {:positions [[0 0 0] [0 1 0]]
                  :rotation-center [0.5 0.0 0.5]
                  :tesr-use-raw-rotation-center? true}
    :common {:physical {:material :metal
                       :hardness 3.0
                       :resistance 6.0
                       :requires-tool true
                       :harvest-tool :pickaxe
                       :harvest-level 1
                       :sounds :metal}}
    :controller {:registry-name "wind_gen_base"
                 :rendering {:flat-item-icon? true
             :render-shape :invisible
                             :textures {:all (modid/asset-path "block" "windgen_base")}}
                 :events {:on-right-click wind-logic/open-wind-base-gui!}}
    :part {:registry-name "wind_gen_base_part"
          :rendering {:model-parent "minecraft:block/cube_all"
            :render-shape :invisible
                       :textures {:all (modid/asset-path "block" "windgen_base")}}
           :events {:on-right-click wind-logic/open-wind-base-gui!}}))

(defn init-wind-gen! []
  (machine-reg/init-machine!
    {:guard ::init
     :log-label "Wind Generator (main/base multiblock + pillar)"
     :tiles [{:id "wind-gen-main"
              :registry-name "wind_gen_main"
              :blocks ["wind-gen-main" "wind-gen-main-part"]
              :tick-fn wind-logic/main-tick-fn
              :read-nbt-fn wind-logic/main-scripted-load-fn
              :write-nbt-fn wind-logic/main-scripted-save-fn}
             {:id "wind-gen-base"
              :registry-name "wind_gen_base"
              :blocks ["wind-gen-base" "wind-gen-base-part"]
              :tick-fn wind-logic/base-tick-fn
              :read-nbt-fn wind-logic/base-scripted-load-fn
              :write-nbt-fn wind-logic/base-scripted-save-fn}
             {:id "wind-gen-pillar"
              :registry-name "wind_gen_pillar"
              :blocks ["wind-gen-pillar"]
              :tick-fn wind-logic/pillar-tick-fn
              :read-nbt-fn wind-logic/pillar-scripted-load-fn
              :write-nbt-fn wind-logic/pillar-scripted-save-fn}]
     :tile-ids ["wind-gen-base"]
     :containers {"wind-gen-main" wind-logic/main-container-fns
                  "wind-gen-base" wind-logic/base-container-fns}
     :capabilities [{:key :wireless-generator
                     :interface IWirelessGenerator
                     :factory impls/wireless-generator-factory}]
     :after register-wind-multiblocks!
     :blocks [(bdsl/create-block-spec
                "wind-gen-pillar"
                {:registry-name "wind_gen_pillar"
                 :physical {:material :metal
                            :hardness 3.0
                            :resistance 6.0
                            :requires-tool true
                            :harvest-tool :pickaxe
                            :harvest-level 1
                            :sounds :metal}
                 :events {:on-place wind-logic/on-wind-pillar-placed!}
                 :rendering {:model-parent "minecraft:block/cube_all"
                             :textures {:all (modid/asset-path "block" "windgen_pillar")}
                             :flat-item-icon? true
                             :render-shape :invisible}})]
     :network-handler wind-handlers/register-network-handlers!
     :client-renderer 'cn.li.ac.block.wind-gen.render/init!}))
