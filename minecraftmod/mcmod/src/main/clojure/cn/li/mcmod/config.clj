(ns cn.li.mcmod.config
  "Mod-id and resource helper utilities shared across platforms.")

(require '[cn.li.mcmod.platform.resource :as resource])

(def ^:dynamic *mod-id*
  "Primary mod identifier used by resource locations.
   Default: \"my_mod\" (can be overridden via MOD_ID env var)."
  (or (System/getenv "MOD_ID") "my_mod"))

(defn resource-location
  "Create a platform-specific resource identifier.

   ([path]) uses the current *mod-id*
   ([modid path]) uses an explicit namespace."
  ([path]
   (resource/create-resource-location *mod-id* path))
  ([modid path]
   (resource/create-resource-location modid path)))

(defn identifier
  "Backward-compatible alias for `resource-location`."
  ([path]
   (identifier *mod-id* path))
  ([modid path]
   (resource/create-resource-location modid path)))

(defn namespaced-path
  "Create a fully qualified resource path string 'modid:path'."
  ([path]
   (namespaced-path *mod-id* path))
  ([modid path]
   (str modid ":" path)))

(defn asset-path
  "Create an asset resource path string: 'modid:category/filename'."
  ([category filename]
   (asset-path *mod-id* category filename))
  ([modid category filename]
   (namespaced-path modid (str category "/" filename))))

