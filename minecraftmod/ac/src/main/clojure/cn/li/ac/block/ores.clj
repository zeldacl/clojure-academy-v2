(ns cn.li.ac.block.ores
  "Generic ore blocks"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

;; ============================================================================
;; Ore Definitions
;; ============================================================================

;; Constrained Ore (约束金属矿石)
(bdsl/defblock constrained-ore
  :registry-name "constrained_ore"
  :physical {:material :stone
             :hardness 3.0
             :resistance 3.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 2
             :sounds :stone}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "constrained_ore")}
              :flat-item-icon? true})

;; Imaginary Ore (虚像金属矿石)
(bdsl/defblock imaginary-ore
  :registry-name "imaginary_ore"
  :physical {:material :stone
             :hardness 3.0
             :resistance 3.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 2
             :sounds :stone}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "imaginary_ore")}
              :flat-item-icon? true})

;; Reso Ore (共振晶体矿石)
(bdsl/defblock reso-ore
  :registry-name "reso_ore"
  :physical {:material :stone
             :hardness 3.0
             :resistance 3.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :stone}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "reso_ore")}
              :flat-item-icon? true})

(defn init-ores!
  []
  (log/info "Initialized ore blocks"))
