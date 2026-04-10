
(ns cn.li.forge1201.platform-impl-impl
  "Bridge-invoked platform initializer.

  This namespace is intentionally free of direct Minecraft/Forge imports so it can
  be AOT-compiled safely under checkClojure/compileClojure."
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as be]
            [cn.li.forge1201.platform-bindings :as bindings]))


(defonce ^:private initialized? (atom false))

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.

  NOTE: Protocol extension migration is in progress. This function is kept as the
  stable entrypoint for Java SPI provider invocation."
  []
  (when (compare-and-set! initialized? false true)
    (alter-var-root #'nbt/*nbt-factory*
      (constantly {:create-compound bindings/create-nbt-compound
                   :create-list bindings/create-nbt-list}))
    (alter-var-root #'nbt/*nbt-has-key-fn* (constantly bindings/nbt-has-key?))
    (alter-var-root #'pos/*position-factory* (constantly bindings/create-block-pos))
    (alter-var-root #'pos/*pos-above-fn* (constantly bindings/pos-above))
    (alter-var-root #'world/*world-get-tile-entity-fn* (constantly bindings/world-get-tile-entity))
    (alter-var-root #'world/*world-get-block-state-fn* (constantly bindings/world-get-block-state))
    (alter-var-root #'world/*world-set-block-fn* (constantly bindings/world-set-block))
    (alter-var-root #'world/*world-remove-block-fn* (constantly bindings/world-remove-block))
    (alter-var-root #'world/*world-break-block-fn* (constantly bindings/world-break-block))
    (alter-var-root #'world/*world-place-block-by-id-fn* (constantly bindings/world-place-block-by-id))
    (alter-var-root #'world/*world-is-chunk-loaded-fn* (constantly bindings/world-is-chunk-loaded?))
    (alter-var-root #'world/*world-get-day-time-fn* (constantly bindings/world-get-day-time))
    (alter-var-root #'world/*world-is-raining-fn* (constantly bindings/world-is-raining))
    (alter-var-root #'world/*world-is-client-side-fn* (constantly bindings/world-is-client-side))
    (alter-var-root #'world/*world-can-see-sky-fn* (constantly bindings/world-can-see-sky))
    (alter-var-root #'be/*be-get-level-fn* (constantly bindings/be-get-level))
    (alter-var-root #'be/*be-get-world-fn* (constantly bindings/be-get-world))
    (alter-var-root #'be/*be-get-custom-state-fn* (constantly bindings/be-get-custom-state))
    (alter-var-root #'be/*be-set-custom-state-fn* (constantly bindings/be-set-custom-state!))
    (alter-var-root #'be/*be-get-block-id-fn* (constantly bindings/be-get-block-id))
    (alter-var-root #'be/*be-set-changed-fn* (constantly bindings/be-set-changed!))
    (log/info "platform-impl-impl initialized via SPI entrypoint"))
  nil)