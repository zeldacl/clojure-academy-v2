(ns cn.li.mc1201.client.font.msdf-tick
  "Client-tick hooks for MSDF font follow-ups (glow animation, etc.)."
  (:import [cn.li.mc1201.client.font.msdf MsdfFontManager]))

(defn client-tick!
  "Advance MSDF client-side animations. Safe to call every client tick."
  []
  (when (MsdfFontManager/hasFontFace)
    (MsdfFontManager/clientTick)))
