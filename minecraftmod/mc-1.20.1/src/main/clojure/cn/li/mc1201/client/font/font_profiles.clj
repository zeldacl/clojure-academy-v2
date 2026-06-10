(ns cn.li.mc1201.client.font.font-profiles
  "Per-font calibration profiles for visual size consistency.

  Different system fonts have different 字面率 (visual fill ratio within the
  em-square) and anti-aliasing spread characteristics.  A uniform TTF provider
  configuration produces visibly different text across platforms.

  This namespace provides a lookup table of calibrated parameters per font
  family, keyed by the profile keyword produced by system-font-detector.

  Each entry contains:
    :size       — TTF provider glyph height on the font sheet (px)
    :shift      — [x y] offset for baseline alignment
    :oversample — STB internal rasterisation multiplier (higher = sharper edges)
    :color-factor — RGB multiplier for gray/neutral tones (vec fonts render
                    lighter than pixel fonts; factor <1 compensates)

  For the fallback (minecraft:default bitmap font):
    :fallback-scale — multiplied onto the CGUI font-scale to reduce visual size."
  (:require [clojure.string :as str]))

;; ============================================================================
;; Profiles
;; ============================================================================

(def font-profiles
  {:msyh      {:size 9.5 :shift [0.0 1.0] :oversample 2.5 :color-factor 0.82}
   :simhei    {:size 9.5 :shift [0.0 1.0] :oversample 3.0 :color-factor 0.85}
   :pingfang  {:size 10.0 :shift [0.0 1.0] :oversample 2.5 :color-factor 0.80}
   :noto-sans {:size 9.5 :shift [0.0 1.0] :oversample 2.5 :color-factor 0.82}
   :dejavu    {:size 9.5 :shift [0.0 1.0] :oversample 3.0 :color-factor 0.85}})

(def fallback-profile
  {:profile        :mc-default
   :fallback-scale 0.85})

;; ============================================================================
;; Lookup
;; ============================================================================

(defn profile-for
  [font-type]
  (get font-profiles font-type))

;; ============================================================================
;; JSON builder
;; ============================================================================

(defn- build-font-json
  "Build a font JSON string for a TTF provider with the given index and profile."
  [{:keys [size shift oversample]} font-ext index]
  (let [[sx sy] shift]
    (format
     "{\"providers\":[{\"type\":\"ttf\",\"file\":\"my_mod:system_font.%s\",\"size\":%.1f,\"oversample\":%.1f,\"shift\":[%.1f,%.1f],\"skip_mitering\":true,\"index\":%d},{\"type\":\"reference\",\"id\":\"minecraft:default\"}]}"
     font-ext (double size) (double oversample) (double sx) (double sy) (int index))))

(defn build-font-jsons
  [profile font-ext]
  (let [normal-json (build-font-json profile font-ext 1)
        bold-json   (build-font-json profile font-ext 1)
        italic-json normal-json]
    {:normal (.getBytes normal-json "UTF-8")
     :bold   (.getBytes bold-json "UTF-8")
     :italic (.getBytes italic-json "UTF-8")}))
