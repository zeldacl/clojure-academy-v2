(ns cn.li.mcmod.client.render.pose
  "Platform-neutral pose/rotation API for TESR rendering.

  Platform adapters call `install-pose-ops!` once at client bootstrap.
  mcmod code calls the public pose helpers below."
  (:require [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *pose-ops* nil)

(defn- pose-op [k]
  (when *pose-ops* (get *pose-ops* k)))

(defn install-pose-ops!
  "Install pose stack operations. Keys: :y-rotation :x-rotation :z-rotation
  :push-pose :pop-pose :translate :scale :get-matrix — each a fn."
  [ops-map label]
  (prt/install-impl! #'*pose-ops* ops-map (or label "pose-ops"))
  nil)

(defn pose-ops-available? []
  (prt/impl-available? #'*pose-ops*))

(defn call-with-pose-ops [ops f]
  (binding [*pose-ops* ops] (f)))

(defn apply-y-rotation
  [pose-stack angle-degrees]
  (if-let [f (pose-op :y-rotation)]
    (try
      (f pose-stack angle-degrees)
      (catch Exception e
        (log/error "Error applying Y-rotation:" (ex-message e))))
    (log/warn "No platform Y-rotation function bound; skipping rotation")))

(defn apply-x-rotation
  [pose-stack angle-degrees]
  (if-let [f (pose-op :x-rotation)]
    (try
      (f pose-stack angle-degrees)
      (catch Exception e
        (log/error "Error applying X-rotation:" (ex-message e))))
    (log/warn "No platform X-rotation function bound; skipping rotation")))

(defn apply-z-rotation
  [pose-stack angle-degrees]
  (if-let [f (pose-op :z-rotation)]
    (try
      (f pose-stack angle-degrees)
      (catch Exception e
        (log/error "Error applying Z-rotation:" (ex-message e))))
    (log/warn "No platform Z-rotation function bound; skipping rotation")))

(defn push-pose
  [pose-stack]
  (if-let [f (pose-op :push-pose)]
    (try
      (f pose-stack)
      (catch Exception e
        (log/error "Error pushing pose:" (ex-message e))))
    (log/warn "No platform push-pose function bound; skipping push")))

(defn pop-pose
  [pose-stack]
  (if-let [f (pose-op :pop-pose)]
    (try
      (f pose-stack)
      (catch Exception e
        (log/error "Error popping pose:" (ex-message e))))
    (log/warn "No platform pop-pose function bound; skipping pop")))

(defn translate
  [pose-stack x y z]
  (if-let [f (pose-op :translate)]
    (try
      (f pose-stack x y z)
      (catch Exception e
        (log/error "Error translating pose-stack:" (ex-message e))))
    (log/warn "No platform translate function bound; skipping translate")))

(defn scale
  [pose-stack x y z]
  (if-let [f (pose-op :scale)]
    (try
      (f pose-stack x y z)
      (catch Exception e
        (log/error "Error scaling pose-stack:" (ex-message e))))
    (log/warn "No platform scale function bound; skipping scale")))

(defn get-matrix
  [pose-stack]
  (if-let [f (pose-op :get-matrix)]
    (try
      (f pose-stack)
      (catch Exception e
        (log/error "Error getting pose-stack matrix:" (ex-message e))
        (throw e)))
    (throw (ex-info "No platform matrix accessor bound for pose-stack"
                    {:hint "Call install-pose-ops! during client platform init"}))))
