(ns cn.li.mc1201.client.font.msdf-setup
  "MSDF shadow font initialization (replaces TTF virtual pack).

  Detects a system TrueType font via pure file IO, loads it with STB, and
  builds the isolated MSDF FontSet used by CGUI text rendering."
  (:require [cn.li.mc1201.client.font.system-font-detector :as detector]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mcmod.util.log :as log])
  (:import [cn.li.mc1201.client.font.msdf MsdfFontManager]))

(defonce ^:private setup-called? (atom false))

(defn init!
  "One-time MSDF font pipeline setup on the client."
  []
  (when (compare-and-set! setup-called? false true)
    (try
      (if-let [{:keys [path]} (detector/detect-system-font)]
        (let [ok (.init MsdfFontManager path)]
          (cgui-font/set-msdf-base-height! (float MsdfFontManager/CGUI_BASE_HEIGHT))
          (if ok
            (log/info "MSDF shadow font initialized from %s" (str path))
            (log/warn "MSDF font face loaded but shader/font not ready; CGUI will use vanilla fallback")))
        (do
          (cgui-font/set-msdf-base-height! (float MsdfFontManager/CGUI_BASE_HEIGHT))
          (log/info "No system font detected; CGUI MSDF unavailable, using vanilla font")))
      (catch Exception e
        (log/error "MSDF font setup failed: %s" (ex-message e))
        (cgui-font/set-msdf-base-height! (float MsdfFontManager/CGUI_BASE_HEIGHT))))))

(defn msdf-ready?
  []
  (MsdfFontManager/isAvailable))

(defn has-font-face?
  []
  (MsdfFontManager/hasFontFace))
