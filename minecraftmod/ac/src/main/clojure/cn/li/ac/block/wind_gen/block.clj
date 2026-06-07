(ns cn.li.ac.block.wind-gen.block
  "Wind Generator blocks thin coordinator."
  (:require [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.wind-gen.handlers :as wind-handlers]
            [cn.li.ac.block.wind-gen.logic :as wind-logic]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defonce-guard wind-gen-installed?)

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
                 :events {:on-right-click wind-logic/open-wind-main-gui!}}
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
    {:guard wind-gen-installed?
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
     ;; :wireless-generator capability is declared by the first generator that loads
     ;; (typically solar-gen). We declare it here only if not already present, then
     ;; always register this tile for the capability key.
     :after (fn []
              (register-wind-multiblocks!)
              (when-not (cn.li.mcmod.platform.capability/get-capability-entry :wireless-generator)
                (cn.li.mcmod.platform.capability/declare-capability!
                  :wireless-generator IWirelessGenerator
                  (fn [be _side] (impls/->WirelessGeneratorImpl be))))
              (doseq [tile-id ["wind-gen-base"]]
                (tile-logic/register-tile-capability! tile-id :wireless-generator)))
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
