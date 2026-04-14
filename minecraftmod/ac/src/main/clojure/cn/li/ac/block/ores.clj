(ns cn.li.ac.block.ores
  "Generic ore blocks"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Ore Definitions
;; ============================================================================

;; Avoid top-level registration side effects during namespace load.
(defonce ^:private ores-installed? (atom false))

(defn init-ores!
  []
  (when (compare-and-set! ores-installed? false true)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "constrained-ore"
        {:registry-name "constrained_ore"
         :physical {:material :stone
                    :hardness 3.0
                    :resistance 3.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "constrained_ore")}
                     :flat-item-icon? true}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "imaginary-ore"
        {:registry-name "imaginary_ore"
         :physical {:material :stone
                    :hardness 3.0
                    :resistance 3.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "imaginary_ore")}
                     :flat-item-icon? true}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "reso-ore"
        {:registry-name "reso_ore"
         :physical {:material :stone
                    :hardness 3.0
                    :resistance 3.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "reso_ore")}
                     :flat-item-icon? true}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "crystal-ore"
        {:registry-name "crystal_ore"
         :physical {:material :stone
                    :hardness 3.0
                    :resistance 3.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :stone}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "crystal_ore")}
                     :flat-item-icon? true}}))
    (log/info "Initialized ore blocks")))
