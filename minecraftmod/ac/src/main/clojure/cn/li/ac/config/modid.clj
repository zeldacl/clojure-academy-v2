(ns cn.li.ac.config.modid
  "Centralized mod-id configuration for easy portability across projects.
   Change MOD-ID here to update mod identification across all modules."
  (:require [cn.li.mcmod.platform.resource :as resource]))

;; ============================================================================
;; Central Mod ID Configuration
;; ============================================================================

(def ^:const MOD-ID
  "The primary mod identifier used across all resource locations, registries,
   and mod identification. Change this value to adapt the codebase for
   different mod projects."
  (or (System/getenv "MOD_ID") "my_mod"))

;; ============================================================================
;; Utility Functions for Resource Location
;; ============================================================================

(defn resource-location
  "Create a platform-specific resource identifier.
   
   Args:
     - path: String path (e.g., 'blocks/matrix')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
  Returns: Resource identifier object with correct namespace
   
   Example:
     (resource-location 'blocks/matrix')
     ;; => ResourceLocation('my_mod:blocks/matrix')"
  ([path]
   (resource-location MOD-ID path))
  ([modid path]
   (resource/create-resource-location modid path)))

(defn identifier
  "Create a platform-specific resource identifier.
   Kept for backward compatibility with existing call sites.
   
   Args:
     - path: String path (e.g., 'blocks/matrix')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
  Returns: Resource identifier object with correct namespace
   
   Example:
     (identifier 'blocks/matrix')
     ;; => Identifier('my_mod:blocks/matrix')"
  ([path]
   (identifier MOD-ID path))
  ([modid path]
   (resource/create-resource-location modid path)))

(defn namespaced-path
  "Create a fully qualified resource path with mod namespace.
   
   Args:
     - path: String path (e.g., 'blocks/matrix')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
   Returns: String in format 'modid:path'
   
   Example:
     (namespaced-path 'blocks/matrix')
     ;; => 'my_mod:blocks/matrix'"
  ([path]
   (namespaced-path MOD-ID path))
  ([modid path]
   (str modid ":" path)))

(defn asset-path
  "Create an asset resource path for GUI/texture resources.
   
   Args:
     - category: String category (e.g., 'textures', 'gui', 'blockstates')
     - filename: String filename (e.g., 'matrix.png')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
   Returns: String in format 'modid:category/filename'
   
   Example:
     (asset-path 'textures' 'blocks/matrix.png')
     ;; => 'my_mod:textures/blocks/matrix.png'"
  ([category filename]
   (asset-path MOD-ID category filename))
  ([modid category filename]
   (namespaced-path modid (str category "/" filename))))
