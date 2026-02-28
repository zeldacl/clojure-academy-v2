(ns my-mod.client.resources
  "Client-side resource loading utilities for models and textures"
  (:import [cn.lambdalib2.render.obj ObjLegacyRender]
           [net.minecraft.util ResourceLocation]))

;; ============================================================================
;; ResourceLocation Helpers
;; ============================================================================

(defn resource-location
  "Create a ResourceLocation in my_mod namespace
  
  Args:
  - loc: String path (e.g., 'textures/models/matrix.png')
  
  Returns: ResourceLocation"
  [loc]
  (ResourceLocation. "my_mod" loc))

;; ============================================================================
;; Model Loading
;; ============================================================================

(defn load-obj-model
  "Load an OBJ model from assets
  
  Args:
  - model-name: String name without extension (e.g., 'matrix')
  
  Returns: ObjLegacyRender instance"
  [model-name]
  (ObjLegacyRender. (resource-location (str "models/" model-name ".obj"))))

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

