(ns cn.li.ac.client.font-init
  "Register AC CGui font keywords for vanilla FontManager.

  At build time my_mod:ac_normal resolves to minecraft:default (bitmap).
  At runtime a virtual resource pack (SystemFontVirtualPack) overrides the
  font definition with a calibrated ttf provider when a system TrueType font
  is detected."
  (:require [cn.li.mc1201.gui.cgui.font :as font]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]))

(defonce ^:private fonts-initialized? (atom false))

(def ^:private ac-normal-id
  (ResourceLocation. "my_mod" "ac_normal"))

(defn- register-ac-fonts!
  [^ResourceLocation loc]
  (let [spec {:location loc}]
    (font/register-font! :ac-normal spec)
    (font/register-font! :ac-bold (assoc spec :bold true))
    (font/register-font! :ac-italic (assoc spec :italic true))))

(defn init-fonts!
  "Register :ac-normal / :ac-bold / :ac-italic for CGui."
  []
  (when (compare-and-set! fonts-initialized? false true)
    (try
      (register-ac-fonts! ac-normal-id)
      (log/info "AC fonts registered (my_mod:ac_normal)")
      (catch Exception e
        (log/error "Failed to initialize AC fonts:" (ex-message e))))))
