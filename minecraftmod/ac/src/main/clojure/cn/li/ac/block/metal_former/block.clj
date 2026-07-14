(ns cn.li.ac.block.metal-former.block
  "Metal Former block - thin coordinator."
  (:require [cn.li.ac.block.machine.registration :as machine-reg]
            [cn.li.ac.block.metal-former.handlers :as former-handlers]
            [cn.li.ac.block.metal-former.logic :as former-logic]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.block.dsl :as bdsl]))

(defn init-metal-former!
  []
  (machine-reg/init-machine!
    {:guard ::init
     :log-label "Metal Former"
     :tiles [{:id "metal-former"
              :registry-name "metal_former"
              :blocks ["metal-former"]
              :tick-fn former-logic/former-tick-fn
              :read-nbt-fn former-logic/former-scripted-load-fn
              :write-nbt-fn former-logic/former-scripted-save-fn}]
     :containers {"metal-former" former-logic/former-container-fns}
     :blocks [(bdsl/create-block-spec
                "metal-former"
                {:registry-name "metal_former"
                 :physical {:material :stone
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
                 :events {:on-right-click former-logic/open-former-gui!}})]
     :network-handler former-handlers/register-network-handlers!}))
