 (ns cn.li.mcmod.client.render.pose
  "Platform-neutral pose/rotation API for TESR rendering.

  Platform adapters must call `register-y-rotation!` with a function that
  accepts `[pose-stack angle-degrees]` and applies the rotation to the
  provided `pose-stack` using platform-specific APIs.

  mcmod code should call `apply-y-rotation` to rotate around the Y axis.
  If no implementation is registered, rotation is skipped and a warning is
  logged."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:dynamic *y-rotation-fn* nil)

(def ^:dynamic *push-pose-fn* nil)
(def ^:dynamic *pop-pose-fn* nil)
(def ^:dynamic *translate-fn* nil)

(def ^:dynamic *get-matrix-fn* nil)

(defn apply-y-rotation
  "Apply Y-axis rotation to `pose-stack` using the platform-provided
  function stored in the root var `*y-rotation-fn*`.

  Platform `platform_impl.clj` files should assign this var via
  `alter-var-root` during platform initialization, e.g.:

    (alter-var-root #'cn.li.mcmod.client.render.pose/*y-rotation-fn*
      (constantly (fn [pose-stack angle]
                    ;; Use the platform axis constant for +Y here.
                    (.mulPose pose-stack (.rotationDegrees <platform-y-axis> (float angle))))))

  If no platform implementation is set, this function logs a warning and
  skips rotation.
  "
  [pose-stack angle-degrees]
  (if *y-rotation-fn*
    (try
      (*y-rotation-fn* pose-stack angle-degrees)
      (catch Exception e
        (log/error "Error applying Y-rotation:" ((ex-message e)))))
    (log/warn "No platform Y-rotation function bound; skipping rotation")))

(defn push-pose
  "Push current pose onto the matrix stack using platform implementation.

  Args:
  - pose-stack: platform pose stack object
  "
  [pose-stack]
  (if *push-pose-fn*
    (try
      (*push-pose-fn* pose-stack)
      (catch Exception e
        (log/error "Error pushing pose:" ((ex-message e)))))
    (log/warn "No platform push-pose function bound; skipping push")))

(defn pop-pose
  "Pop pose from the matrix stack using platform implementation."
  [pose-stack]
  (if *pop-pose-fn*
    (try
      (*pop-pose-fn* pose-stack)
      (catch Exception e
        (log/error "Error popping pose:" ((ex-message e)))))
    (log/warn "No platform pop-pose function bound; skipping pop")))

(defn translate
  "Translate the pose-stack by (x y z) using platform implementation.

  All arguments should be numeric.
  "
  [pose-stack x y z]
  (if *translate-fn*
    (try
      (*translate-fn* pose-stack x y z)
      (catch Exception e
        (log/error "Error translating pose-stack:" ((ex-message e)))))
    (log/warn "No platform translate function bound; skipping translate")))

(defn get-matrix
  "Return the current transformation matrix object for `pose-stack`.

  Platform implementations should bind `*get-matrix-fn*` to a function that
  accepts a platform `PoseStack` and returns the current matrix/pose entry or
  matrix object used by vertex submission code. If unbound, throws an
  informative exception.
  "
  [pose-stack]
  (if *get-matrix-fn*
    (try
      (*get-matrix-fn* pose-stack)
      (catch Exception e
        (log/error "Error getting pose-stack matrix:" ((ex-message e)))
        (throw e)))
    (throw (ex-info "No platform matrix accessor bound for pose-stack"
                    {:hint "Call platform-impl/init-platform! to bind pose functions"}))))