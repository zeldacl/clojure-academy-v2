(ns cn.li.combat.composite-loader
  "combat-core's thin binding of cn.li.node.composite-loader to real EDN
   resources: supplies cn.li.mcmod.runtime.safe-edn/read-resource! as the
   default document-loader (node-core itself cannot depend on mcmod, so it
   takes the reader as an injected parameter -- see that namespace's
   docstring), so callers only need to name a manifest resource path.

   Combat's actual :layer :mid composite content (the reusable component
   library any ability document can call into) lives as data under AC's
   resource tree (ac/src/main/resources/ac/combat/composites_v3/), the
   same split the pre-existing v2 system already uses for its own
   :composite documents (ac/.../components/*.edn, loaded by
   cn.li.combat.recipe/load-composites!) -- combat-core owns the loading
   engine, AC owns the content."
  (:require [cn.li.mcmod.runtime.safe-edn :as safe-edn]
            [cn.li.node.composite-loader :as loader]))

(defn install!
  "Load and register every :layer :mid composite `manifest-resource`
   lists. Returns {:registered [...] :errors [...]}, see
   cn.li.node.composite-loader/install!."
  ([manifest-resource]
   (install! manifest-resource safe-edn/read-resource!))
  ([manifest-resource document-loader]
   (loader/install! {:manifest-resource manifest-resource :document-loader document-loader})))
