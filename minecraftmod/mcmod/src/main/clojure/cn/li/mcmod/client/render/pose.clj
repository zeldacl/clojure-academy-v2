(ns cn.li.mcmod.client.render.pose
  "Required pose/rotation operations for client rendering.

   The operation table is written to Framework during bootstrap, while the
   concrete IFns are published into private Vars for render-time calls."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(def pose-ops-keys #{:push-pose :pop-pose :translate :scale
                      :y-rotation :x-rotation :z-rotation :axis-rotation :get-matrix})

;; These Vars are populated once by install-pose-ops!.  Render calls below use
;; them directly: no Framework atom dereference, map lookup, or apply/seq
;; allocation occurs on the normal path.
(def ^:private push-pose-fn nil)
(def ^:private pop-pose-fn nil)
(def ^:private translate-fn nil)
(def ^:private scale-fn nil)
(def ^:private y-rotation-fn nil)
(def ^:private x-rotation-fn nil)
(def ^:private z-rotation-fn nil)
(def ^:private axis-rotation-fn nil)
(def ^:private get-matrix-fn nil)

(def ^:private pose-operation-vars
  {:push-pose #'push-pose-fn
   :pop-pose #'pop-pose-fn
   :translate #'translate-fn
   :scale #'scale-fn
   :y-rotation #'y-rotation-fn
   :x-rotation #'x-rotation-fn
   :z-rotation #'z-rotation-fn
   :axis-rotation #'axis-rotation-fn
   :get-matrix #'get-matrix-fn})

(defn- missing-operation [k]
  (throw (ex-info "Required pose operation is not installed"
                  {:operation k})))

(defn install-pose-ops!
  [ops-map _label]
  (if-let [fw-atom (fw/fw-atom)]
    (let [missing (seq (remove (set (keys ops-map)) pose-ops-keys))]
      (swap! fw-atom assoc-in [:platform :pose-ops] ops-map)
      (doseq [[k target-var] pose-operation-vars]
        (alter-var-root target-var (constantly (get ops-map k))))
      (log/debug "Pose ops installed:" (pr-str (keys ops-map)))
      (when missing
        (log/error "Pose ops MISSING required keys:" (pr-str missing))))
    (log/error "Pose ops install FAILED: Framework atom nil")))

(defn pose-ops-available? []
  (boolean push-pose-fn))

(defn apply-y-rotation [pose-stack angle-degrees]
  ((or y-rotation-fn (missing-operation :y-rotation)) pose-stack angle-degrees))

(defn apply-x-rotation [pose-stack angle-degrees]
  ((or x-rotation-fn (missing-operation :x-rotation)) pose-stack angle-degrees))

(defn apply-z-rotation [pose-stack angle-degrees]
  ((or z-rotation-fn (missing-operation :z-rotation)) pose-stack angle-degrees))

(defn apply-axis-rotation [pose-stack angle-degrees ax ay az]
  ((or axis-rotation-fn (missing-operation :axis-rotation))
   pose-stack angle-degrees ax ay az))

(defn push-pose [pose-stack]
  ((or push-pose-fn (missing-operation :push-pose)) pose-stack))

(defn pop-pose [pose-stack]
  ((or pop-pose-fn (missing-operation :pop-pose)) pose-stack))

(defn translate [pose-stack x y z]
  ((or translate-fn (missing-operation :translate)) pose-stack x y z))

(defn scale [pose-stack x y z]
  ((or scale-fn (missing-operation :scale)) pose-stack x y z))

(defn get-matrix [pose-stack]
  ((or get-matrix-fn (missing-operation :get-matrix)) pose-stack))
