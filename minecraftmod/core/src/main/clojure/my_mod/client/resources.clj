(ns my-mod.client.resources
  "Client-side resource loading utilities for models and textures"
  (:require [my-mod.config.modid :as modid]
            [my-mod.client.obj :as obj]))

;; ============================================================================
;; ResourceLocation Helpers
;; ============================================================================

(defn resource-location
  "Create a platform resource identifier in my_mod namespace
  
  Args:
  - loc: String path (e.g., 'textures/models/matrix.png')
  
  Returns: resource identifier"
  [loc]
  (modid/resource-location "my_mod" loc))

;; ============================================================================
;; Model Loading
;; ============================================================================

(defn load-obj-model
  "Load an OBJ model from assets
  
  Args:
  - model-name: String name without extension (e.g., 'matrix')
  
  Returns: parsed OBJ model map"
  [model-name]
  (obj/load-obj-model (str "models/" model-name ".obj")))

;; ============================================================================
;; Texture Loading
;; ============================================================================

(defn texture-location
  "Get texture ResourceLocation
  
  Args:
  - loc: String path without extension (e.g., 'models/matrix')
  
  Returns: ResourceLocation"
  [loc]
  (resource-location (str "textures/" loc ".png")))

