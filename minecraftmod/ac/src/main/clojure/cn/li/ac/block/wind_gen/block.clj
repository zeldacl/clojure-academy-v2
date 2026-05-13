(ns cn.li.ac.block.wind-gen.block
  "Wind Generator blocks thin coordinator."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.wind-gen.config :as wind-config]
            [cn.li.ac.block.wind-gen.logic :as wind-logic]
            [cn.li.ac.block.wind-gen.handlers :as wind-handlers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))


(defonce-guard wind-gen-installed?)

(defn init-wind-gen! []
  (with-init-guard wind-gen-installed?
    (let [msg-registry (requiring-resolve 'cn.li.ac.wireless.gui.message.registry/register-block-messages!)]
      (msg-registry :wind-gen [:get-status-main :get-status-base]))

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-main"
        {:registry-name "wind_gen_main"
         :impl :scripted
         :blocks ["wind-gen-main" "wind-gen-main-part"]
         :tick-fn wind-logic/main-tick-fn
         :read-nbt-fn wind-logic/main-scripted-load-fn
         :write-nbt-fn wind-logic/main-scripted-save-fn}))

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-base"
        {:registry-name "wind_gen_base"
         :impl :scripted
         :blocks ["wind-gen-base" "wind-gen-base-part"]
         :tick-fn wind-logic/base-tick-fn
         :read-nbt-fn wind-logic/base-scripted-load-fn
         :write-nbt-fn wind-logic/base-scripted-save-fn}))

    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "wind-gen-pillar"
        {:registry-name "wind_gen_pillar"
         :impl :scripted
         :blocks ["wind-gen-pillar"]
         :tick-fn wind-logic/pillar-tick-fn
         :read-nbt-fn wind-logic/pillar-scripted-load-fn
         :write-nbt-fn wind-logic/pillar-scripted-save-fn}))

    (platform-cap/declare-capability! :wind-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "wind-gen-base" :wind-generator)

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
                               :textures {:all (modid/asset-path "block" "wind_gen_main")}}
                   :events {:on-right-click wind-logic/open-wind-main-gui!}}
      :part {:registry-name "wind_gen_main_part"
             :rendering {:model-parent "minecraft:block/cube_all"
                         :textures {:all (modid/asset-path "block" "wind_gen_main")}}
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
                               :textures {:all (modid/asset-path "block" "wind_gen_base")}}
                   :events {:on-right-click wind-logic/open-wind-base-gui!}}
      :part {:registry-name "wind_gen_base_part"
             :rendering {:model-parent "minecraft:block/cube_all"
                         :textures {:all (modid/asset-path "block" "wind_gen_base")}}
             :events {:on-right-click wind-logic/open-wind-base-gui!}})

    (bdsl/register-block!
      (bdsl/create-block-spec
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
                     :textures {:all (modid/asset-path "block" "wind_gen_pillar")}
                     :flat-item-icon? true}}))

    (hooks/register-network-handler! wind-handlers/register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.wind-gen.render/init!)
    (log/info "Initialized Wind Generator blocks (main/base multiblock + pillar)")))