(ns my-mod.client.render.pose
  "Platform-neutral pose/rotation API for TESR rendering.

  Platform adapters must call `register-y-rotation!` with a function that
  accepts `[pose-stack angle-degrees]` and applies the rotation to the
  provided `pose-stack` using platform-specific APIs.

  mcmod code should call `apply-y-rotation` to rotate around the Y axis.
  If no implementation is registered, rotation is skipped and a warning is
  logged."
  (:require [my-mod.util.log :as log]))

(def ^:dynamic *y-rotation-fn* nil)

(defn apply-y-rotation
  "Apply Y-axis rotation to `pose-stack` using the platform-provided
  function stored in the root var `*y-rotation-fn*`.

  Platform `platform_impl.clj` files should assign this var via
  `alter-var-root` during platform initialization, e.g.:

    (alter-var-root #'my-mod.client.render.pose/*y-rotation-fn*
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
        (log/error "Error applying Y-rotation:" (.getMessage e))))
    (log/warn "No platform Y-rotation function bound; skipping rotation")))