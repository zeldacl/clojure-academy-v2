(ns my-mod.items
  "Item definitions - migrated to Item DSL
  
  This namespace is deprecated and kept only for backward compatibility.
  
  For modern item definitions, use:
  - my-mod.item.dsl/defitem - DSL macro for defining items
  - my-mod.item.demo - Example items showing various use cases
  
  Example:
  (require '[my-mod.item.dsl :as idsl])
  
  (idsl/defitem my-custom-item
    :max-stack-size 16
    :creative-tab :tools
    :durability 500
    :on-use (fn [data] (println \"Used!\")))")

;; All item definitions have been migrated to my-mod.item.dsl and my-mod.item.demo
