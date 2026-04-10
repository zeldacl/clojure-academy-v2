(ns cn.li.forge1201.platform-impl-impl
  "Bridge-invoked platform initializer.

  This namespace is intentionally free of direct Minecraft/Forge imports so it can
  be AOT-compiled safely under checkClojure/compileClojure."
  (:require [cn.li.mcmod.util.log :as log]))


(defonce ^:private initialized? (atom false))

(defn init-platform!
  "Initialize Forge 1.20.1 platform implementations.

  NOTE: Protocol extension migration is in progress. This function is kept as the
  stable entrypoint for Java SPI provider invocation."
  []
  (when (compare-and-set! initialized? false true)
    (log/info "platform-impl-impl initialized via SPI entrypoint"))
  nil)