(ns cn.li.ac.blocks
  "Block definitions - migrated to Block DSL
  
  This namespace is deprecated and kept only for backward compatibility.
  
  For modern block definitions, use:
  - cn.li.mcmod.block.dsl/defblock - DSL macro for defining blocks
  
  Example:
  (require '[cn.li.mcmod.block.dsl :as bdsl])
  
  (bdsl/defblock my-custom-block
    :material :stone
    :hardness 3.0
    :resistance 10.0
    :light-level 15
    :requires-tool true
    :on-right-click (fn [data] (println \"Clicked!\")))")

;; All block definitions have been migrated to cn.li.mcmod.block.dsl
