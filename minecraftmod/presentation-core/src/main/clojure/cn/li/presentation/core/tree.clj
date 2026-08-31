(ns cn.li.presentation.core.tree
  "Shared deterministic traversal of primitive UI children and named slots.

   Slots are flattened after ordinary children in lexical slot order. This
   keeps layout, paint, hit testing, and scrollbar lookup on one tree view
   while preserving stable ordering for immutable compositions.")

(defn ordered-children
  "Return ordinary children followed by named-slot entries in stable order."
  [node]
  (into (vec (or (:children node) []))
        (mapcat second
                (sort-by (comp str first) (or (:slots node) {})))))
