(ns my-mod.blocks
  "Block definitions and factory functions")

(defn create-demo-block-properties
  "Return a map of properties for the demo block (version-agnostic)"
  []
  {:material :stone
   :hardness 1.5
   :resistance 6.0
   :requires-tool true})

(defn create-demo-block
  "Factory function to create demo block - actual implementation by adapter"
  [properties]
  ;; This is a placeholder; actual Block instance created by version adapter
  {:type :block
   :id "demo_block"
   :properties properties})
