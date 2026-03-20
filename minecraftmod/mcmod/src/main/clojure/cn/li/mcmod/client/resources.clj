(ns cn.li.mcmod.client.resources
  "Client-side resource loading utilities for models and textures"
  (:require [cn.li.mcmod.platform.resource :as res]
            [cn.li.mcmod.client.obj :as obj]))

;; ============================================================================
;; ResourceLocation Helpers
;; ============================================================================
;; Uses res/invoke-resource-location so ac/loader can inject *resource-location-fn*
;; (e.g. bound to modid/resource-location) without mcmod depending on config.modid.

(defn resource-location
  "Create a platform resource identifier in default (mod) namespace
  
  Args:
  - loc: String path (e.g., 'textures/models/matrix.png')
  
  Returns: resource identifier"
  [loc]
  (res/invoke-resource-location nil loc))

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

