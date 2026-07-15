(ns cn.li.mcmod.config
  "Mod-id and resource helper utilities shared across platforms.
   The mod-id value is sourced from cn.li.mcmod.ModId/ID,
   which is generated at build time from gradle.properties mod_id.")

(require '[cn.li.mcmod.platform.resource :as resource])

(def mod-id
  "Primary mod identifier used by resource locations.
   Sourced from cn.li.mcmod.ModId (generated from gradle.properties)."
  cn.li.mcmod.ModId/ID)

(defn resource-location
  "Create a platform-specific resource identifier.

   ([path]) uses the current mod-id
   ([modid path]) uses an explicit namespace."
  ([path]
   (resource/create-resource-location mod-id path))
  ([modid path]
   (resource/create-resource-location modid path)))

(defn namespaced-path
  "Create a fully qualified resource path string 'modid:path'."
  ([path]
   (namespaced-path mod-id path))
  ([modid path]
   (str modid ":" path)))

(defn asset-path
  "Create an asset resource path string: 'modid:category/filename'."
  ([category filename]
   (asset-path mod-id category filename))
  ([modid category filename]
   (namespaced-path modid (str category "/" filename))))

