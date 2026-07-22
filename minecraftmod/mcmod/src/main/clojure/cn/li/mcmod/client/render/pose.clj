(ns cn.li.mcmod.client.render.pose
  "Pose/rotation API for TESR rendering via Framework function map.

   Pose ops stored at [:platform :pose-ops]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def pose-ops-keys #{:push-pose :pop-pose :translate :scale
                      :y-rotation :x-rotation :z-rotation :axis-rotation :get-matrix})

(defn- pose-op [k]
  (get-in @(fw/fw-atom) [:platform :pose-ops k]))

(defn install-pose-ops!
  [ops-map _label]
  (if-let [fw-atom (fw/fw-atom)]
    (let [missing (seq (remove (set (keys ops-map)) pose-ops-keys))]
      (swap! fw-atom assoc-in [:platform :pose-ops] ops-map)
      (log/info "Pose ops installed:" (pr-str (keys ops-map)))
      (when missing
        (log/error "Pose ops MISSING required keys:" (pr-str missing))))
    (log/error "Pose ops install FAILED: Framework atom nil")))

(defn pose-ops-available? []
  (boolean (get-in @(fw/fw-atom) [:platform :pose-ops])))

(defn apply-y-rotation [pose-stack angle-degrees]
  (if-let [f (pose-op :y-rotation)]
    (try (f pose-stack angle-degrees)
         (catch Exception e
           (log/error "Error applying Y-rotation:" (ex-message e))
           (log/stacktrace "Error applying Y-rotation" e)))
    (log/warn "No platform Y-rotation function bound; skipping rotation")))

(defn apply-x-rotation [pose-stack angle-degrees]
  (if-let [f (pose-op :x-rotation)]
    (try (f pose-stack angle-degrees)
         (catch Exception e
           (log/error "Error applying X-rotation:" (ex-message e))
           (log/stacktrace "Error applying X-rotation" e)))
    (log/warn "No platform X-rotation function bound; skipping rotation")))

(defn apply-z-rotation [pose-stack angle-degrees]
  (if-let [f (pose-op :z-rotation)]
    (try (f pose-stack angle-degrees)
         (catch Exception e
           (log/error "Error applying Z-rotation:" (ex-message e))
           (log/stacktrace "Error applying Z-rotation" e)))
    (log/warn "No platform Z-rotation function bound; skipping rotation")))

(defn apply-axis-rotation [pose-stack angle-degrees ax ay az]
  (if-let [f (pose-op :axis-rotation)]
    (try (f pose-stack angle-degrees ax ay az)
         (catch Exception e
           (log/error "Error applying axis-rotation:" (ex-message e))
           (log/stacktrace "Error applying axis-rotation" e)))
    (log/warn "No platform axis-rotation function bound; skipping rotation")))

(defn push-pose [pose-stack]
  (if-let [f (pose-op :push-pose)]
    (try (f pose-stack)
         (catch Exception e
           (log/error "Error pushing pose:" (ex-message e))
           (log/stacktrace "Error pushing pose" e)))
    (log/warn "No platform push-pose function bound; skipping push")))

(defn pop-pose [pose-stack]
  (if-let [f (pose-op :pop-pose)]
    (try (f pose-stack)
         (catch Exception e
           (log/error "Error popping pose:" (ex-message e))
           (log/stacktrace "Error popping pose" e)))
    (log/warn "No platform pop-pose function bound; skipping pop")))

(defn translate [pose-stack x y z]
  (if-let [f (pose-op :translate)]
    (try (f pose-stack x y z)
         (catch Exception e
           (log/error "Error translating pose-stack:" (ex-message e))
           (log/stacktrace "Error translating pose-stack" e)))
    (log/warn "No platform translate function bound; skipping translate")))

(defn scale [pose-stack x y z]
  (if-let [f (pose-op :scale)]
    (try (f pose-stack x y z)
         (catch Exception e
           (log/error "Error scaling pose-stack:" (ex-message e))
           (log/stacktrace "Error scaling pose-stack" e)))
    (log/warn "No platform scale function bound; skipping scale")))

(defn get-matrix [pose-stack]
  (if-let [f (pose-op :get-matrix)]
    (try (f pose-stack)
         (catch Exception e
           (log/error "Error getting pose-stack matrix:" (ex-message e))
           (log/stacktrace "Error getting pose-stack matrix" e)
           (throw e)))
    (throw (ex-info "No platform matrix accessor bound for pose-stack"
                    {:hint "Call install-pose-ops! during client platform init"}))))
