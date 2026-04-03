(ns cn.li.ac.registry.content-namespaces
  "Centralized list of all content namespaces (blocks, items, GUIs).

  When adding new content:
  1. Add namespace to appropriate list below
  2. Add hook registration in the namespace file
  3. No changes to cn.li.ac.core/init needed unless load order must change")

(def block-namespaces
  "All block definition namespaces.
  These define blocks using defblock DSL and may register network handlers."
  '[cn.li.ac.content.blocks.wireless
    cn.li.ac.content.blocks.generators
    cn.li.ac.content.blocks.crafting
    cn.li.ac.content.blocks.ability
    cn.li.ac.content.blocks.misc
    cn.li.ac.content.blocks.integration])

(def item-namespaces
  "All item definition namespaces.
  These define items using defitem DSL."
  '[cn.li.ac.content.items.all])

(def ability-namespaces
  "Ability content namespaces.
  These define categories and skills via ability DSL."
  '[cn.li.ac.content.ability])

(def system-namespaces
  "System feature namespaces (terminal, etc).
  These provide game systems and frameworks."
  '[cn.li.ac.terminal.init])

(defn load-all!
  "Load all content namespaces to trigger DSL macro side effects and hook registration."
  []
  (doseq [ns-sym (concat block-namespaces item-namespaces ability-namespaces system-namespaces)]
    (require ns-sym))
  ;; Initialize terminal system after all content is loaded
  (when-let [init-fn (requiring-resolve 'cn.li.ac.terminal.init/init-terminal!)]
    (init-fn)))
