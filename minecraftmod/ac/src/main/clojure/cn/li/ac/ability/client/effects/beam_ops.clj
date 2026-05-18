(ns cn.li.ac.ability.client.effects.beam-ops
  "Higher-level beam/ray render operation helpers for client ability FX.

  `beam-render` owns the primitive billboard implementation. This namespace owns
  the repeated ability-side concerns around style maps: RGB + alpha composition,
  fading ttl/max-ttl beams, direct beam rendering, and small glow-line helpers
  used by trajectory previews."
  (:require [cn.li.ac.ability.client.effects.beam-render :as beam-render]
            [cn.li.ac.ability.client.render-util :as ru]))

(def default-glow-line-texture
  "my_mod:textures/effects/glow_line.png")

(defn resolve-value
  "Resolve either a constant value or a `[context life]` callback."
  [value context life]
  (if (fn? value)
    (value context life)
    value))

(defn rgba
  "Compose an RGB map and alpha into a clamped RGBA map."
  [rgb alpha]
  (ru/with-alpha rgb alpha))

(defn color-fn
  "Create a beam-render callback from RGB and alpha constants/functions."
  [rgb alpha]
  (fn [context life]
    (rgba (resolve-value rgb context life)
          (resolve-value alpha context life))))

(defn- style-color
  [style color-key rgb-key alpha-key]
  (if (contains? style color-key)
    (get style color-key)
    (when (contains? style rgb-key)
      (color-fn (get style rgb-key) (get style alpha-key 255)))))

(defn render-style
  "Convert an ability FX style map into the lower-level beam-render options.

  Supports either direct `:*color` maps/callbacks or split `:*rgb` + `:*alpha`
  entries. Alpha entries may be constants or `[beam life]` callbacks for fading
  beams. Width/core entries may also be constants or callbacks."
  [style]
  {:texture (:texture style)
   :width (:width style)
   :core-width (:core-width style)
   :core-ratio (:core-ratio style)
   :outer-color (style-color style :outer-color :outer-rgb :outer-alpha)
   :inner-color (style-color style :inner-color :inner-rgb :inner-alpha)
   :line-color (style-color style :line-color :line-rgb :line-alpha)})

(defn- resolve-render-style
  [style context life]
  (->> (render-style style)
       (map (fn [[k v]] [k (resolve-value v context life)]))
       (into {})))

(defn beam-ops
  "Build a non-fading beam from explicit start/end positions and a style map."
  [cam-pos start end style]
  (beam-render/beam-ops cam-pos start end (resolve-render-style style {:start start :end end} 1.0)))

(defn fading-beam-ops
  "Build a fading beam/ray from a state map containing :start/:end/:ttl/:max-ttl."
  [cam-pos beam style]
  (beam-render/fading-beam-ops cam-pos beam (render-style style)))

(defn fading-beams-ops
  "Build fading beam/ray ops for a collection of beam state maps."
  [cam-pos beams style]
  (mapcat #(fading-beam-ops cam-pos % style) beams))

(defn fade-alpha
  "Return an indexed fade alpha compatible with trajectory/ribbon previews.

  Defaults mirror the existing VecAccel ribbon fade: raw alpha starts at 0.7,
  decreases by 0.021 per segment, and is scaled to 0..255."
  ([idx]
   (fade-alpha idx {}))
  ([idx {:keys [start step scale]}]
   (let [raw-alpha (max 0.0 (- (double (or start 0.7))
                               (* (double idx) (double (or step 0.021)))))]
     (int (* raw-alpha (double (or scale 255.0)))))))

(defn glow-line-quad-op
  "Build the standard glow-line textured quad used by trajectory previews."
  ([p0 p1 p2 p3 color]
   (glow-line-quad-op p0 p1 p2 p3 color default-glow-line-texture))
  ([p0 p1 p2 p3 color texture]
   (ru/quad-op texture p0 p1 p2 p3 color)))
