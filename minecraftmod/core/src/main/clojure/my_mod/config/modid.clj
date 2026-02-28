(ns my-mod.config.modid
  "Centralized mod-id configuration for easy portability across projects.
   Change MOD-ID here to update mod identification across all modules."
  (:require [clojure.string :as str]))

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
  "Create a Minecraft ResourceLocation (net.minecraft.util.ResourceLocation).
   
   Args:
     - path: String path (e.g., 'blocks/matrix')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
   Returns: ResourceLocation instance with correct namespace
   
   Example:
     (resource-location 'blocks/matrix')
     ;; => ResourceLocation('my_mod:blocks/matrix')"
  ([path]
   (resource-location MOD-ID path))
  ([modid path]
   (let [new-ctor (fn []
                    (let [cls (Class/forName "net.minecraft.resources.ResourceLocation")
                          ctor (.getConstructor cls (into-array Class [String String]))]
                      (.newInstance ctor (object-array [modid path]))))
         old-ctor (fn []
                    (let [cls (Class/forName "net.minecraft.util.ResourceLocation")
                          ctor (.getConstructor cls (into-array Class [String String]))]
                      (.newInstance ctor (object-array [modid path]))))]
     (try
       (new-ctor)
       (catch Exception _
         (old-ctor))))))

(defn identifier
  "Create a Fabric Identifier (net.minecraft.util.Identifier).
   This is Fabric's equivalent to ResourceLocation.
   
   Args:
     - path: String path (e.g., 'blocks/matrix')
     - modid (optional): String mod identifier, defaults to MOD-ID
   
   Returns: Identifier instance with correct namespace
   
   Example:
     (identifier 'blocks/matrix')
     ;; => Identifier('my_mod:blocks/matrix')"
  ([path]
   (identifier MOD-ID path))
  ([modid path]
   ;; Use reflection for compatibility - class name varies by platform/mappings
   (try
     (let [id-cls (Class/forName "net.minecraft.util.Identifier")
           ctor (.getConstructor id-cls (into-array Class [String String]))]
       (.newInstance ctor (object-array [modid path])))
     (catch Exception _
       ;; Fallback: Use ResourceLocation on Forge/MojMap
       (resource-location modid path)))))

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
