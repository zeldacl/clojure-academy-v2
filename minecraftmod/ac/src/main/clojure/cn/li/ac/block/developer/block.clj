(ns cn.li.ac.block.developer.block
  (:require [cn.li.ac.block.developer.handlers :as dev-handlers]
            [cn.li.ac.block.developer.logic :as dev-logic]
            [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.util.init-guard :refer [defonce-guard]]
            [cn.li.mcmod.block.dsl :as bdsl])
  (:import [cn.li.acapi.wireless IWirelessReceiver]))

(defonce-guard developer-installed?)

(defn- developer-multiblock-positions []
  dev-logic/developer-multiblock-positions)

(defn- developer-block-spec [id registry-name tier-kw]
  (bdsl/create-block-spec
    id
    {:multi-block {:positions (developer-multiblock-positions)
                   :rotation-center [0.5 0.0 0.5]
                   :pivot-xz-override [0.0 0.0]
                   :tesr-use-raw-rotation-center? true
                   :tesr-y-deg-override 0.0
                   :mode :controller-parts
                   :controller-block-id id
                   :part-block-id (str id "-part")}
     :registry-name registry-name
     :physical {:material :metal :hardness 4.0 :resistance 10.0
                :requires-tool true :harvest-tool :pickaxe :harvest-level 2 :sounds :metal}
     :rendering {:light-level (if (= tier-kw :advanced) 2.0 1.0)
                 :flat-item-icon? true
                 :textures {:all (modid/asset-path "block" (if (= tier-kw :advanced) "dev_advanced" "dev_normal"))}}
     :events {:on-right-click (dev-logic/open-developer-gui-for id)}}))

(defn init-developer!
  []
  (machine-reg/init-machine!
    {:guard developer-installed?
     :log-label "Developer blocks"
     :tiles [{:id "developer-normal"
              :registry-name "developer_normal"
              :blocks ["developer-normal" "developer-normal-part"]
              :tick-fn dev-logic/developer-tick-fn
              :read-nbt-fn dev-logic/dev-scripted-load-fn
              :write-nbt-fn dev-logic/dev-scripted-save-fn}
             {:id "developer-advanced"
              :registry-name "developer_advanced"
              :blocks ["developer-advanced" "developer-advanced-part"]
              :tick-fn dev-logic/developer-tick-fn
              :read-nbt-fn dev-logic/dev-scripted-load-fn
              :write-nbt-fn dev-logic/dev-scripted-save-fn}]
     :tile-ids ["developer-normal" "developer-advanced"]
     :capabilities [{:key :wireless-receiver :interface IWirelessReceiver
                     :factory dev-logic/create-dev-receiver-cap}]
     :blocks [(developer-block-spec "developer-normal" "developer_normal" :normal)
              (developer-block-spec "developer-normal-part" "developer_normal_part" :normal)
              (developer-block-spec "developer-advanced" "developer_advanced" :advanced)
              (developer-block-spec "developer-advanced-part" "developer_advanced_part" :advanced)]
     :network-handler dev-handlers/register-network-handlers!
     :client-renderer 'cn.li.ac.block.developer.render/init!}))
