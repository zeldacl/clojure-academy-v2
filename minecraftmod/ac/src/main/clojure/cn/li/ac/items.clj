(ns cn.li.ac.items
  "Item definitions - migrated to Item DSL
  
  This namespace is deprecated and kept only for backward compatibility.
  
  For modern item definitions, use:
  - cn.li.mcmod.item.dsl/defitem - DSL macro for defining items
  
  Example:
  (require '[cn.li.mcmod.item.dsl :as idsl])
  
  (idsl/defitem my-custom-item
    :max-stack-size 16
    :creative-tab :tools
    :durability 500
    :on-use (fn [data] (println \"Used!\")))")

;; All item definitions have been migrated to cn.li.mcmod.item.dsl
