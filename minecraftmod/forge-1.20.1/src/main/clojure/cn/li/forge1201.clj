(ns cn.li.forge1201
  "Forge 1.20.1 platform-specific implementation")

(defn ensure-mod-loaded!
  "Load the Forge mod namespace lazily to avoid triggering mod class initialization
  during Clojure compile/check phases."
  []
  (requiring-resolve 'cn.li.forge1201.mod/mod-init)
  nil)
