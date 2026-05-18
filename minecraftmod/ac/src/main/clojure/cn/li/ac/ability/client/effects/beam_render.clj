(ns cn.li.ac.ability.client.effects.beam-render
  "Shared client-side beam render operation builders for ability FX."
  (:require [cn.li.ac.ability.client.render-util :as ru]))

(defn life-ratio
  "Return ttl/max-ttl clamped only by denominator safety."
  [ttl max-ttl]
  (/ (double ttl) (double (max 1 max-ttl))))

(defn beam-ops
  "Build standard billboard beam ops from explicit render options."
  [cam-pos start end {:keys [width core-width core-ratio outer-color inner-color line-color texture]}]
  (ru/billboard-beam-ops cam-pos start end
    {:texture texture
     :width width
     :core-width core-width
     :core-ratio core-ratio
     :outer-color outer-color
     :inner-color inner-color
     :line-color line-color}))

(defn fading-beam-ops
  "Build beam ops from a beam state carrying :start/:end/:ttl/:max-ttl.

  Config callbacks receive `[beam life]` and return values for the corresponding
  billboard beam option. Constant values are also accepted."
  [cam-pos {:keys [start end ttl max-ttl] :as beam} {:keys [width core-width core-ratio outer-color inner-color line-color texture]}]
  (let [life (life-ratio ttl max-ttl)
        resolve-value (fn [value]
                        (if (fn? value) (value beam life) value))]
    (beam-ops cam-pos start end
      {:texture (resolve-value texture)
       :width (resolve-value width)
       :core-width (resolve-value core-width)
       :core-ratio (resolve-value core-ratio)
       :outer-color (resolve-value outer-color)
       :inner-color (resolve-value inner-color)
       :line-color (resolve-value line-color)})))
