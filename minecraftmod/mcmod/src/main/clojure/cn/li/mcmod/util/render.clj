(ns my-mod.util.render
  "Rendering utilities - OpenGL and texture helpers"
  (:require [my-mod.util.log :as log]))

(defonce ^:private gl11-class (delay (Class/forName "org.lwjgl.opengl.GL11")))
(defonce ^:private texture-binder* (atom nil))
(defonce ^:private texture-binder-warned* (atom false))

(defn register-texture-binder!
  "Register platform/client texture bind function.

  Signature: (fn [texture] ... )"
  [binder-fn]
  (reset! texture-binder* binder-fn))

(defn- gl-static [method-name arg-types args]
  (clojure.lang.Reflector/invokeStaticMethod
   @gl11-class
   method-name
   (to-array args)))

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
  (gl-static "glPushMatrix" [] []))

(defn gl-pop-matrix
  "Pop matrix from matrix stack
  
  Returns: nil"
  []
  (gl-static "glPopMatrix" [] []))

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
  (gl-static "glTranslated" [Double/TYPE Double/TYPE Double/TYPE]
             [(double x) (double y) (double z)]))

(defn gl-rotate
  "Rotate the current matrix around an axis
  
  Args:
  - angle: double - rotation angle in degrees
  - x: double - X component of rotation axis
  - y: double - Y component of rotation axis
  - z: double - Z component of rotation axis
  
  Returns: nil"
  [angle x y z]
  (gl-static "glRotated" [Double/TYPE Double/TYPE Double/TYPE Double/TYPE]
             [(double angle) (double x) (double y) (double z)]))

(defn gl-scale
  "Scale the current matrix
  
  Args:
  - x: double - X scale factor
  - y: double - Y scale factor
  - z: double - Z scale factor
  
  Returns: nil"
  [x y z]
  (gl-static "glScaled" [Double/TYPE Double/TYPE Double/TYPE]
             [(double x) (double y) (double z)]))

(defn gl-begin-triangles
  "Begin GL triangle drawing." 
  []
  (let [mode (int 4)]
    (gl-static "glBegin" [Integer/TYPE] [mode])))

(defn gl-end
  "End current GL drawing block." 
  []
  (gl-static "glEnd" [] []))

(defn gl-normal
  "Submit vertex normal." 
  [x y z]
  (gl-static "glNormal3f" [Float/TYPE Float/TYPE Float/TYPE]
             [(float x) (float y) (float z)]))

(defn gl-tex-coord
  "Submit UV coordinate." 
  [u v]
  (gl-static "glTexCoord2f" [Float/TYPE Float/TYPE]
             [(float u) (float v)]))

(defn gl-vertex
  "Submit vertex position." 
  [x y z]
  (gl-static "glVertex3f" [Float/TYPE Float/TYPE Float/TYPE]
             [(float x) (float y) (float z)]))

;; ============================================================================
;; Texture Binding
;; ============================================================================

(defn bind-texture
  "Bind a texture for rendering
  
  Args:
  - texture: ResourceLocation or String path
  
  Returns: nil"
  [texture]
  (if-let [binder @texture-binder*]
    (binder texture)
    (when (compare-and-set! texture-binder-warned* false true)
      (log/warn "Texture binder not registered; skipping bind" texture))))

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
