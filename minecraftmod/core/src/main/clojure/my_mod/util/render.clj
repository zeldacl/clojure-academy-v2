(ns my-mod.util.render
  "Rendering utilities - OpenGL and texture helpers"
  (:import [org.lwjgl.opengl GL11]
           [cn.lambdalib2.util RenderUtils]))

;; ============================================================================
;; Time Helper
;; ============================================================================

(defn get-render-time
  "Get current render time in seconds
  
  Returns: double - seconds since game start"
  []
  (/ (double (System/currentTimeMillis)) 1000.0))

;; ============================================================================
;; Matrix Stack Operations
;; ============================================================================

(defn gl-push-matrix
  "Push current matrix onto matrix stack
  
  Returns: nil"
  []
  (GL11/glPushMatrix))

(defn gl-pop-matrix
  "Pop matrix from matrix stack
  
  Returns: nil"
  []
  (GL11/glPopMatrix))

;; ============================================================================
;; Transformations
;; ============================================================================

(defn gl-translate
  "Translate the current matrix
  
  Args:
  - x: double - X offset
  - y: double - Y offset
  - z: double - Z offset
  
  Returns: nil"
  [x y z]
  (GL11/glTranslated (double x) (double y) (double z)))

(defn gl-rotate
  "Rotate the current matrix around an axis
  
  Args:
  - angle: double - rotation angle in degrees
  - x: double - X component of rotation axis
  - y: double - Y component of rotation axis
  - z: double - Z component of rotation axis
  
  Returns: nil"
  [angle x y z]
  (GL11/glRotated (double angle) (double x) (double y) (double z)))

(defn gl-scale
  "Scale the current matrix
  
  Args:
  - x: double - X scale factor
  - y: double - Y scale factor
  - z: double - Z scale factor
  
  Returns: nil"
  [x y z]
  (GL11/glScaled (double x) (double y) (double z)))

;; ============================================================================
;; Texture Binding
;; ============================================================================

(defn bind-texture
  "Bind a texture for rendering
  
  Args:
  - texture: ResourceLocation or String path
  
  Returns: nil"
  [texture]
  (RenderUtils/loadTexture texture))

;; ============================================================================
;; Convenience Macros
;; ============================================================================

(defmacro with-matrix
  "Execute body with matrix push/pop
  
  Usage:
    (with-matrix
      (gl-translate 0 1 0)
      (render-model))
  
  Args:
  - body: Forms to execute between push/pop
  
  Returns: Result of last form in body"
  [& body]
  `(do
     (gl-push-matrix)
     (try
       ~@body
       (finally
         (gl-pop-matrix)))))
