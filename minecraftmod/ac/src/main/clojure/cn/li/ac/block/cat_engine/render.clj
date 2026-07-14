(ns cn.li.ac.block.cat-engine.render
  "CLIENT-ONLY: Cat Engine scripted renderer.

  Renders a floating rotating quad using the cat_engine block texture.
  Behavior mirrors legacy AcademyCraft TESR animation speed driven by :this-tick-gen."
  (:require [cn.li.ac.block.machine.render-runtime :as machine-render-runtime]
            [cn.li.mcmod.client.resources :as res]
            [cn.li.mcmod.client.render.tesr-api :as tesr-api]
            [cn.li.mcmod.client.render.buffer :as rb]
            [cn.li.mcmod.client.render.pose :as pose]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]))

(def ^:private cat-engine-resources-holder nil)
(def ^:private cat-engine-resources
  (machine-render-runtime/lazy-resources #'cat-engine-resources-holder
    {:texture #(res/texture-location "block/cat_engine")}))

(def ^:private rotor-cache-key :rotor-cache)

(defn rotor-cache-atom
  []
  (machine-render-runtime/render-cache-atom rotor-cache-key {}))

(defn rotor-cache-snapshot
  []
  (machine-render-runtime/render-cache-snapshot rotor-cache-key {}))

(defn clear-rotor-cache!
  []
  (machine-render-runtime/clear-render-cache! rotor-cache-key {}))

(defn reset-rotor-cache-for-test!
  ([]
   (clear-rotor-cache!))
  ([cache]
   (machine-render-runtime/reset-render-cache-for-test! rotor-cache-key {} cache)))

(defn- tile-key [tile]
  (let [p (pos/position-get-block-pos tile)]
    [(pos/pos-x p) (pos/pos-y p) (pos/pos-z p)]))

(defn- next-rotation!
  [tile tick-gen]
  (let [k (tile-key tile)
        now-ms (long (* 1000.0 (render/get-render-time)))
        prev (get (rotor-cache-snapshot) k {:t now-ms :rot 0.0})
        dt-ms (max 0 (- now-ms (:t prev)))
        rot (mod (+ (double (:rot prev))
                    (* (double dt-ms) (double tick-gen) 1.0e-2))
                 360.0)]
    (swap! (rotor-cache-atom) assoc k {:t now-ms :rot rot})
    rot))

(def ^:private quad-vertices
  [[0.0 0.0 0.0 0.0 0.0]
   [1.0 0.0 0.0 1.0 0.0]
   [1.0 1.0 0.0 1.0 1.0]
   [0.0 1.0 0.0 0.0 1.0]])

(def ^:private quad-indices [0 1 2 0 2 3])

(defn- submit-quad!
  [vc pose-stack packed-light packed-overlay]
  (doseq [idx quad-indices
          :let [[x y z u v] (nth quad-vertices idx)]]
    (rb/submit-vertex vc pose-stack x y z
                      1.0 1.0 1.0 1.0
                      u v
                      packed-overlay packed-light
                      0.0 0.0 1.0)))

(defn render-at-origin
  [tile pose-stack buffer-source packed-light packed-overlay]
  (let [state (or (platform-be/get-custom-state tile) {})
        tick-gen (double (get state :this-tick-gen 0.0))
        rot (next-rotation! tile tick-gen)
        t (render/get-render-time)
        bob (* 0.03 (Math/sin (* t 0.006)))
        p (pos/position-get-block-pos tile)
        wx (+ 0.5 (double (pos/pos-x p)))
        wz (+ 0.5 (double (pos/pos-z p)))
        yaw-deg (+ 180.0 (Math/toDegrees (Math/atan2 wx wz)))
        vc (rb/get-cutout-no-cull-buffer buffer-source (:texture (cat-engine-resources)))]
    (pose/push-pose pose-stack)
    (try
      (pose/translate pose-stack 0.5 (+ 0.03 bob) 0.5)
      ;; Preserve original RenderCatEngine behavior: rotate quad by a world-pos-derived yaw
      ;; so the rotor plane doesn't stay in a fixed axis-aligned orientation.
      (pose/apply-y-rotation pose-stack yaw-deg)
      (pose/translate pose-stack 0.0 0.5 0.0)
      (pose/apply-x-rotation pose-stack rot)
      (pose/translate pose-stack -0.5 -0.5 0.0)
      (submit-quad! vc pose-stack packed-light packed-overlay)
      (finally
        (pose/pop-pose pose-stack)))))

(defn register!
  []
  (tesr-api/register-scripted-tile-renderer!
    "cat-engine"
    {:render-tile (fn [tile-entity _partial-ticks pose-stack buffer-source packed-light packed-overlay]
                     (try
                       (render-at-origin tile-entity pose-stack buffer-source packed-light packed-overlay)
                       (catch Exception e
                         (log/error "Error in cat-engine renderer:" (ex-message e)))))}))

(defn init!
  []
  (machine-render-runtime/register-client-renderer-init! 'cn.li.ac.block.cat-engine.render/register!))
