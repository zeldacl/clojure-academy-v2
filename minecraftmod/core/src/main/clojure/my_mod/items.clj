(ns my-mod.items
  "Item definitions and factory functions")

(defn create-demo-item-properties
  "Return a map of properties for the demo item (version-agnostic)"
  []
  {:max-stack-size 64
   :tab :misc})

(defn create-demo-item
  "Factory function to create demo item - actual implementation by adapter"
  [properties]
  ;; Placeholder; actual Item instance created by version adapter
  {:type :item
   :id "demo_item"
   :properties properties})
