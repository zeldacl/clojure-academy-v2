(ns cn.li.ac.registry.content-namespaces
  "Centralized content namespace + init registration.

  The `content-load-plan` value is the single source of truth for both:
  - namespace require order
  - post-require init function execution order"
  (:require [cn.li.ac.registry.content-plan-builder :as plan-builder]
            [cn.li.ac.registry.spi.content-phase :as content-phase-spi]
            [cn.li.mcmod.util.log :as log]))

(def ^:private default-phase-plugins
  [{:phase :block
    :namespaces '[cn.li.ac.content.blocks.wireless
                  cn.li.ac.content.blocks.generators
                  cn.li.ac.content.blocks.crafting
                  cn.li.ac.content.blocks.ability
                  cn.li.ac.content.blocks.misc
                  cn.li.ac.content.blocks.integration]
    :init-fns '[cn.li.ac.content.blocks.wireless/init-wireless-blocks!
                cn.li.ac.content.blocks.generators/init-generator-blocks!
                cn.li.ac.content.blocks.crafting/init-crafting-blocks!
                cn.li.ac.content.blocks.ability/init-ability-blocks!
                cn.li.ac.content.blocks.misc/init-misc-blocks!
                cn.li.ac.content.blocks.integration/init-integration-blocks!]}
   {:phase :item
    :namespaces '[cn.li.ac.content.items.all
                  cn.li.ac.content.sounds]
    :init-fns '[cn.li.ac.content.items.all/init-items!
                cn.li.ac.content.sounds/init-sounds!]}
   {:phase :entity
    :namespaces '[cn.li.ac.content.render-profiles.effect-profiles
            cn.li.ac.content.entities.all]
    :init-fns '[cn.li.ac.content.render-profiles.effect-profiles/init-render-profiles!
          cn.li.ac.content.entities.all/init-entities!]}
   {:phase :ability
    :namespaces '[cn.li.ac.content.ability
                  cn.li.ac.content.effects
                  cn.li.ac.content.particles
                  cn.li.ac.content.loot]
    :init-fns '[cn.li.ac.content.ability/init-ability-content!
                cn.li.ac.content.effects/init-effects!
                cn.li.ac.content.particles/init-particles!
                cn.li.ac.content.loot/init-loot!]}
   {:phase :achievement
    :namespaces '[cn.li.ac.achievement.data
                  cn.li.ac.achievement.dispatcher]
    :init-fns '[cn.li.ac.achievement.dispatcher/init-dispatcher!]}
   {:phase :system
    :namespaces '[cn.li.ac.terminal.init]
    :init-fns '[cn.li.ac.terminal.init/init-terminal!]
    :trace-tag :terminal-init}])

(doseq [phase-spec default-phase-plugins]
  (content-phase-spi/declare-content-phase! phase-spec))

(defn register-content-phase-plugin!
  "Public extension point for content phase registration."
  [phase-spec]
  (content-phase-spi/declare-content-phase! phase-spec)
  (plan-builder/build-load-plan))

(defn current-content-load-plan
  "Return latest materialized content load plan."
  []
  (content-phase-spi/list-content-phases))

(def content-load-plan
  (current-content-load-plan))

(defn- trace-tag [{:keys [phase trace-tag]}]
  (or trace-tag (keyword (str (name phase) "-init"))))

(defn- require-namespaces! [namespaces]
  (doseq [ns-sym namespaces]
    (try
      (log/warn "[CONTENT_TRACE] require begin" ns-sym)
      (require ns-sym)
      (log/warn "[CONTENT_TRACE] require ok" ns-sym)
      (catch Throwable t
        (log/error "[CONTENT_TRACE] require fail" ns-sym (ex-message t))
        (throw t)))))

(defn- run-init-fns! [{:keys [init-fns] :as phase-spec}]
  (let [tag (name (trace-tag phase-spec))]
    (doseq [init-sym init-fns]
      (try
        (log/warn (str "[CONTENT_TRACE] " tag " begin") init-sym)
        (when-let [init-fn (requiring-resolve init-sym)]
          (init-fn)
          (log/warn (str "[CONTENT_TRACE] " tag " ok") init-sym))
        (catch Throwable t
          (log/error (str "[CONTENT_TRACE] " tag " fail") init-sym (ex-message t))
          (throw t))))))

(defn load-all!
  "Load all content namespaces and execute their declared init functions."
  []
  (let [plan (current-content-load-plan)]
    (log/warn "[CONTENT_TRACE] load-all begin" {:phases (mapv :phase plan)})
    (doseq [phase-spec plan]
    (require-namespaces! (:namespaces phase-spec)))
    (doseq [phase-spec plan]
    (run-init-fns! phase-spec))
    (log/warn "[CONTENT_TRACE] load-all end")))
