(ns cn.li.mcmod.block.dsl-presets
  "Block preset templates for common patterns.
   Provides convenient builders for ore blocks, wood, metal, multiblocks, etc."
  (:require [cn.li.mcmod.block.dsl-multiblock :as mb]))

;; ============================================================================
;; Simple Material Presets
;; ============================================================================

(defn ore-preset
  "Create an ore block preset with common properties"
  [harvest-level]
  {:material :stone
   :hardness 3.0
   :resistance 3.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :stone})

(defn wood-preset
  "Create a wood block preset with common properties"
  []
  {:material :wood
   :hardness 2.0
   :resistance 3.0
   :requires-tool false
   :harvest-tool :axe
   :harvest-level 0
   :sounds :wood})

(defn metal-preset
  "Create a metal block preset with common properties"
  [harvest-level]
  {:material :metal
   :hardness 5.0
   :resistance 6.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :metal})

(defn glass-preset
  "Create a glass block preset with common properties"
  []
  {:material :glass
   :hardness 0.3
   :resistance 0.3
   :requires-tool false
   :sounds :glass})

(defn light-block-preset
  "Create a light-emitting block preset"
  [light-level]
  {:material :glass
   :hardness 1.0
   :resistance 1.0
   :light-level light-level
   :sounds :glass})

;; ============================================================================
;; Multi-block Presets
;; ============================================================================

(defn multi-block-preset
  "Create a regular multi-block preset
   size: {:width 2 :height 3 :depth 2}
   Example: (multi-block-preset {:width 3 :height 4 :depth 3})"
  [size & additional-options]
  (merge
    {:multi-block? true
     :multi-block-size size
     :multi-block-origin {:x 0 :y 0 :z 0}
     :material :metal
     :hardness 5.0
     :resistance 10.0
     :requires-tool true
     :harvest-tool :pickaxe}
    (apply merge additional-options)))

(defn irregular-multi-block-preset
  "Create an irregular multi-block preset with custom positions
   positions: [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0} {:x 0 :y 1 :z 0} ...]
   Example: (irregular-multi-block-preset [{:x 0 :y 0 :z 0} {:x 1 :y 1 :z 0}])"
  [positions & additional-options]
  (merge
    {:multi-block? true
     :multi-block-positions (mb/normalize-positions positions)
     :multi-block-origin {:x 0 :y 0 :z 0}
     :material :metal
     :hardness 5.0
     :resistance 10.0
     :requires-tool true
     :harvest-tool :pickaxe}
    (apply merge additional-options)))

;; ============================================================================
;; Preset Combination
;; ============================================================================

(defn merge-presets
  "Merge multiple presets with options"
  [& preset-and-options]
  (apply merge preset-and-options))
