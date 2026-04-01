(ns cn.li.ac.registry.content-namespaces
  "Centralized list of all content namespaces (blocks, items, GUIs).

  When adding new content:
  1. Add namespace to appropriate list below
  2. Add hook registration in the namespace file
  3. No changes to core.clj needed")

(def block-namespaces
  "All block definition namespaces.
  These define blocks using defblock DSL and may register network handlers."
  '[cn.li.ac.content.blocks.wireless])

(def item-namespaces
  "All item definition namespaces.
  These define items using defitem DSL."
  '[cn.li.ac.content.items.all])

(defn load-all!
  "Load all content namespaces to trigger DSL macro side effects and hook registration."
  []
  (doseq [ns-sym (concat block-namespaces item-namespaces)]
    (require ns-sym)))
