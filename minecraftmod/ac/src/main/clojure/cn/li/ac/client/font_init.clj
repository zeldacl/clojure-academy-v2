(ns cn.li.ac.client.font-init
  "Register AC CGui font keywords for vanilla FontManager.

  Three font locations are registered, each mapped to a separate JSON file
  injected by `SystemFontVirtualPack` at runtime:
    :ac-normal → my_mod:ac_normal  (TTF index 0 — regular weight)
    :ac-bold   → my_mod:ac_bold    (TTF index 1 — true bold glyphs from TTC)
    :ac-italic → my_mod:ac_italic  (TTF index 0 + Style.withItalic for GPU shear)

  When no system font is available the virtual pack is absent; all three fall
  back to minecraft:default (bitmap)."
  (:require [cn.li.mc1201.gui.cgui.font :as font]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]))

(defonce ^:private fonts-initialized? (atom false))

(defn- register-ac-fonts!
  []
  (font/register-font! :ac-normal
    {:location (ResourceLocation. "my_mod" "ac_normal")})
  (font/register-font! :ac-bold
    {:location (ResourceLocation. "my_mod" "ac_bold")})
  (font/register-font! :ac-italic
    {:location (ResourceLocation. "my_mod" "ac_italic")
     :italic true}))

(defn init-fonts!
  "Register :ac-normal / :ac-bold / :ac-italic for CGui."
  []
  (when (compare-and-set! fonts-initialized? false true)
    (try
      (register-ac-fonts!)
      (log/info "AC fonts registered (my_mod:ac_normal, ac_bold, ac_italic)")
      (catch Exception e
        (log/error "Failed to initialize AC fonts:" (ex-message e))))))
