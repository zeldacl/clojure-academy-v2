(ns cn.li.ac.registry.content-namespaces
  "Centralized list of all content namespaces (blocks, items, GUIs).

  When adding new content:
  1. Add namespace to appropriate list below
  2. Add hook registration in the namespace file
  3. No changes to cn.li.ac.core/init needed unless load order must change")

(require '[cn.li.mcmod.util.log :as log])

(def block-namespaces
  "All block definition namespaces.
  These define blocks using defblock DSL and may register network handlers."
  '[cn.li.ac.content.blocks.wireless
    cn.li.ac.content.blocks.generators
    cn.li.ac.content.blocks.crafting
    cn.li.ac.content.blocks.ability
    cn.li.ac.content.blocks.misc
    cn.li.ac.content.blocks.integration])

  (def block-init-fns
    '[cn.li.ac.content.blocks.wireless/init-wireless-blocks!
      cn.li.ac.content.blocks.generators/init-generator-blocks!
      cn.li.ac.content.blocks.crafting/init-crafting-blocks!
      cn.li.ac.content.blocks.ability/init-ability-blocks!
      cn.li.ac.content.blocks.misc/init-misc-blocks!
      cn.li.ac.content.blocks.integration/init-integration-blocks!])

(def item-namespaces
  "All item definition namespaces.
  These define items using defitem DSL."
  '[cn.li.ac.content.items.all
    cn.li.ac.content.sounds])

(def item-init-fns
  '[cn.li.ac.content.items.all/init-items!
    cn.li.ac.content.sounds/init-sounds!])

(def entity-namespaces
  "Entity content namespaces.
  These define entities using entity DSL."
  '[cn.li.ac.content.entities.all])

(def entity-init-fns
  '[cn.li.ac.content.entities.all/init-entities!])

(def ability-namespaces
  "Ability content namespaces.
  These define categories and skills via ability DSL."
  '[cn.li.ac.content.ability
    cn.li.ac.content.effects
    cn.li.ac.content.particles
    cn.li.ac.content.loot])

(def achievement-namespaces
  "Achievement metadata and dispatcher namespaces."
  '[cn.li.ac.achievement.data
    cn.li.ac.achievement.dispatcher])

(def system-namespaces
  "System feature namespaces (terminal, etc).
  These provide game systems and frameworks."
  '[cn.li.ac.terminal.init])

(def ability-init-fns
  '[cn.li.ac.content.ability/init-ability-content!
    cn.li.ac.content.effects/init-effects!
    cn.li.ac.content.particles/init-particles!
    cn.li.ac.content.loot/init-loot!])

(def achievement-init-fns
  '[cn.li.ac.achievement.dispatcher/init-dispatcher!])

(defn load-all!
  "Load all content namespaces to trigger DSL macro side effects and hook registration."
  []
  (log/warn "[CONTENT_TRACE] load-all begin")
  (doseq [ns-sym (concat block-namespaces item-namespaces entity-namespaces ability-namespaces achievement-namespaces system-namespaces)]
    (try
      (log/warn "[CONTENT_TRACE] require begin" ns-sym)
      (require ns-sym)
      (log/warn "[CONTENT_TRACE] require ok" ns-sym)
      (catch Throwable t
        (log/error "[CONTENT_TRACE] require fail" ns-sym (ex-message t))
        (throw t))))
  (doseq [init-sym block-init-fns]
    (try
      (log/warn "[CONTENT_TRACE] block-init begin" init-sym)
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)
        (log/warn "[CONTENT_TRACE] block-init ok" init-sym))
      (catch Throwable t
        (log/error "[CONTENT_TRACE] block-init fail" init-sym (ex-message t))
        (throw t))))
  (doseq [init-sym item-init-fns]
    (try
      (log/warn "[CONTENT_TRACE] item-init begin" init-sym)
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)
        (log/warn "[CONTENT_TRACE] item-init ok" init-sym))
      (catch Throwable t
        (log/error "[CONTENT_TRACE] item-init fail" init-sym (ex-message t))
        (throw t))))
  (doseq [init-sym entity-init-fns]
    (try
      (log/warn "[CONTENT_TRACE] entity-init begin" init-sym)
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)
        (log/warn "[CONTENT_TRACE] entity-init ok" init-sym))
      (catch Throwable t
        (log/error "[CONTENT_TRACE] entity-init fail" init-sym (ex-message t))
        (throw t))))
  (doseq [init-sym ability-init-fns]
    (try
      (log/warn "[CONTENT_TRACE] ability-init begin" init-sym)
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)
        (log/warn "[CONTENT_TRACE] ability-init ok" init-sym))
      (catch Throwable t
        (log/error "[CONTENT_TRACE] ability-init fail" init-sym (ex-message t))
        (throw t))))
  (doseq [init-sym achievement-init-fns]
    (try
      (log/warn "[CONTENT_TRACE] achievement-init begin" init-sym)
      (when-let [init-fn (requiring-resolve init-sym)]
        (init-fn)
        (log/warn "[CONTENT_TRACE] achievement-init ok" init-sym))
      (catch Throwable t
        (log/error "[CONTENT_TRACE] achievement-init fail" init-sym (ex-message t))
        (throw t))))
  ;; Initialize terminal system after all content is loaded
  (log/warn "[CONTENT_TRACE] terminal-init begin")
  (when-let [init-fn (requiring-resolve 'cn.li.ac.terminal.init/init-terminal!)]
    (init-fn)
    (log/warn "[CONTENT_TRACE] terminal-init ok"))
  (log/warn "[CONTENT_TRACE] load-all end"))
