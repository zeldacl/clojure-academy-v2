(ns cn.li.mc1201.client.font.font-profiles
  "Per-font calibration profiles for visual size consistency.

  Different system fonts have different 字面率 (visual fill ratio within the
  em-square).  Microsoft YaHei fills nearly the entire square; PingFang leaves
  considerable internal padding.  A uniform TTF provider `size` produces visibly
  different text heights across platforms.

  This namespace provides a lookup table of calibrated parameters per font
  family, keyed by the profile keyword produced by system-font-detector.

  Each entry may contain:
    :size  — TTF provider `size` (glyph height on the font sheet, px)
    :shift — TTF provider `shift`  [x y] offset for baseline alignment

  For the fallback (minecraft:default bitmap font), the entry contains:
    :fallback-scale — scale factor multiplied onto the CGUI font-scale to
                      visually match the reference TrueType appearance"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Profiles
;; ============================================================================

(def font-profiles
  "Calibration map: profile keyword → {:size :shift} for TTF providers."
  {:msyh      {:size 12.5 :shift [0.0 1.5]}  ; Microsoft YaHei — reference
   :simhei    {:size 12.5 :shift [0.0 1.5]}  ; SimHei
   :pingfang  {:size 13.0 :shift [0.0 1.5]}  ; PingFang — smaller 字面, bump size
   :noto-sans {:size 12.5 :shift [0.0 1.5]}  ; Noto Sans CJK
   :dejavu    {:size 12.5 :shift [0.0 1.5]}}); DejaVu Sans

(def fallback-profile
  "Profile applied when no system font is available.
    :fallback-scale — multiplied onto the CGUI font-scale to reduce the visual
                      size of the thicker bitmap glyphs so they match the
                      TrueType reference appearance."
  {:profile      :mc-default
   :fallback-scale 0.85})

;; ============================================================================
;; Lookup
;; ============================================================================

(defn profile-for
  "Look up the calibration profile for a font-type keyword.
  Returns nil when the keyword is unknown (caller should use fallback-profile)."
  [font-type]
  (get font-profiles font-type))

;; ============================================================================
;; JSON builder
;; ============================================================================

(defn build-font-json
  "Build the ac_normal.json content string for a given profile and font extension.

  The JSON includes a TTF provider (with calibrated size and shift) followed by a
  minecraft:default reference provider as fallback.

  Uses plain string formatting — no external JSON library required.
  The structure is fixed; only size, shift, and extension vary."
  [{:keys [size shift]} font-ext]
  (let [[sx sy] shift]
    (format
     "{\"providers\":[{\"type\":\"ttf\",\"file\":\"my_mod:system_font.%s\",\"size\":%.1f,\"oversample\":5.0,\"shift\":[%.1f,%.1f],\"index\":0},{\"type\":\"reference\",\"id\":\"minecraft:default\"}]}"
     font-ext (double size) (double sx) (double sy))))
