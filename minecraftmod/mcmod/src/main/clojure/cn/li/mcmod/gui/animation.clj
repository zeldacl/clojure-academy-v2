(ns cn.li.mcmod.gui.animation
  "CLIENT-ONLY: Generic animation system for GUI widgets.

  Must be loaded via side-checked requiring-resolve from platform layer.

  Provides reusable animation state management, frame updates, and rendering
  for sprite-based animations in GUI widgets."
  (:require [cn.li.mcmod.gui.components :as comp]))

(defn create-animation-state
  "Create animation state structure.

  Returns: Map with atoms for current-state, current-frame, and last-update"
  []
  {:current-state (atom :unlinked)
   :current-frame (atom 0)
   :last-update (atom (System/currentTimeMillis))})

(defn update-animation!
  "Update animation frame based on elapsed time.

  Args:
    anim-state - Animation state map from create-animation-state
    config - Animation config map {:begin N :frames N :frame-time ms}

  Side effects: Updates current-frame and last-update atoms when frame advances"
  [anim-state config]
  (let [{:keys [current-frame last-update]} anim-state
        now (System/currentTimeMillis)
        dt (- now @last-update)
        {:keys [frames frame-time]} config]
    (when (>= dt frame-time)
      (swap! current-frame #(mod (inc %) frames))
      (reset! last-update now))))

(defn render-animation-frame!
  "Render sprite frame from vertical texture atlas.

  Args:
    widget - Widget to render to
    texture-path - Path to texture (e.g. \"textures/guis/effect/effect_node.png\")
    x, y, w, h - Widget position and size
    absolute-frame - Current frame index (0-based)
    total-frames - Total frames in vertical atlas

  Texture layout: Frames stacked vertically, each frame is 1/total-frames of height.
  UV: (0, frame/total) to (1, frame/total + 1/total)"
  [widget texture-path x y w h absolute-frame total-frames]
  (let [u0 0.0
        v0 (/ (double absolute-frame) total-frames)
        u1 1.0
        v1 (+ v0 (/ 1.0 total-frames))]
    (comp/render-texture-region
      widget
      texture-path
      x y w h
      u0 v0 u1 v1)))

(defn create-status-poller
  "Create throttled status poller.

  Args:
    query-fn - Function to call on each poll (no args)
    interval-ms - Minimum milliseconds between polls

  Returns: Map with :last-query atom and :update-fn

  The update-fn should be called every frame; it throttles actual queries."
  [query-fn interval-ms]
  (let [last-query (atom (- (System/currentTimeMillis) (+ interval-ms 1000)))]
    {:last-query last-query
     :update-fn (fn []
                  (let [now (System/currentTimeMillis)
                        dt (- now @last-query)]
                    (when (> dt interval-ms)
                      (reset! last-query now)
                      (query-fn))))}))
