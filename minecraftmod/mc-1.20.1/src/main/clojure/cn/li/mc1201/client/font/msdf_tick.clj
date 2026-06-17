(ns cn.li.mc1201.client.font.msdf-tick
  "Client-tick hooks for MSDF font follow-ups (glow animation, etc.)."
  (:import [cn.li.mc1201.client.font.msdf MsdfFontManager]))

(defn client-tick!
  "Advance MSDF client-side animations and retry deferred font init."
  []
  (when-not (MsdfFontManager/isAvailable)
    (when-let [ensure! (requiring-resolve 'cn.li.mc1201.client.font.msdf-setup/ensure-ready!)]
      (ensure!)))
  (when (MsdfFontManager/hasFontFace)
    (MsdfFontManager/clientTick)))
