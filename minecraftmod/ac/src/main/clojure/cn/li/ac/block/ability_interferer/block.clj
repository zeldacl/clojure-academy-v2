(ns cn.li.ac.block.ability-interferer.block
  "Ability Interferer block - thin coordinator."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.block.ability-interferer.logic :as interferer-logic]
            [cn.li.ac.block.ability-interferer.handlers :as interferer-handlers]))

(defonce-guard ability-interferer-installed?)

(defn init-ability-interferer!
  []
  (with-init-guard ability-interferer-installed?
    (msg-registry/register-block-messages! :ability-interferer
      [:get-status :change-range :toggle-enabled :set-whitelist :add-to-whitelist :remove-from-whitelist])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "ability-interferer"
        {:registry-name "ability_interferer"
         :impl :scripted
         :blocks ["ability-interferer"]
         :tick-fn interferer-logic/interferer-tick-fn
         :read-nbt-fn interferer-logic/interferer-scripted-load-fn
         :write-nbt-fn interferer-logic/interferer-scripted-save-fn}))
    (tile-logic/register-container! "ability-interferer" interferer-logic/interferer-container-fns)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "ability-interferer"
        {:registry-name "ability_interferer"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 8.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "ability_interf_off")}
                     :flat-item-icon? true
                     :light-level 3}
         :block-state {:block-state-properties interferer-logic/interferer-block-state-properties}
         :events {:on-right-click interferer-logic/open-interferer-gui!
                  :on-place interferer-logic/on-interferer-placed!
                  :on-break interferer-logic/on-interferer-break!}}))
    (hooks/register-network-handler! interferer-handlers/register-network-handlers!)
    (log/info "Initialized Ability Interferer block")))

