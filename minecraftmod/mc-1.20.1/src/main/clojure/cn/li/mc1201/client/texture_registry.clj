(ns cn.li.mc1201.client.texture-registry
  "Registry for named texture ResourceLocations.

   Delegates path storage to the platform-neutral mcmod registry.
   Resolves keyword→path-string entries into Minecraft ResourceLocations
   for use by mc-1.20.1 renderers."
  (:require [cn.li.mcmod.client.texture-registry :as mcmod-tex]
            [cn.li.mcmod.config :as modid-config]
            [clojure.string :as str])
  (:import [net.minecraft.resources ResourceLocation]))

(defn register-texture!
  "Register a named texture. Delegates to the mcmod platform-neutral registry.

   key is a keyword; path is either 'textures/guis/...' (prefixed with current
   mod-id at resolve time) or 'mod-id:textures/guis/...' (explicit namespace)."
  [key path]
  (mcmod-tex/register-texture! key path))

(defn resolve-texture
  "Return the ResourceLocation registered under key, or nil.

   Reads the path string from the mcmod registry and converts it to a
   ResourceLocation. Paths without an explicit mod-id prefix are resolved
   against the current mod-id."
  [key]
  (when-let [path (mcmod-tex/get-texture-path key)]
    (let [s (str path)]
      (if (str/includes? s ":")
        (let [idx (.indexOf s ":")]
          (ResourceLocation. (subs s 0 idx) (subs s (inc idx))))
        (ResourceLocation. modid-config/mod-id s)))))

(defn reset-texture-registry-for-test!
  "Clear all registered textures. Intended for tests."
  []
  (mcmod-tex/reset-texture-registry-for-test!)
  nil)
