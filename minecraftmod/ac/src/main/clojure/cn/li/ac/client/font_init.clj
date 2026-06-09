(ns cn.li.ac.client.font-init
  "Register AC CGui font keywords for vanilla FontManager.

  When my_mod:ac_normal failed to load (e.g. broken ttf json), register fonts
  without a custom :location so CGui uses the default minecraft font."
  (:require [clojure.java.io :as io]
            [cn.li.mc1201.gui.cgui.font :as font]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.resources ResourceLocation]
           [net.minecraft.client Minecraft]
           [net.minecraft.client.gui.font FontManager]))

(defonce ^:private fonts-initialized? (atom false))

(def ^:private ac-normal-id
  (ResourceLocation. "my_mod" "ac_normal"))

(def ^:private missing-font-id
  (ResourceLocation. "minecraft" "missing"))

(defn- font-set-usable?
  "True when FontManager resolved `loc` to a real font set (not minecraft:missing)."
  [^ResourceLocation loc]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (let [^FontManager fm (.fontManager mc)
            fs (.getFontSet fm loc)
            missing (.getFontSet fm missing-font-id)]
        (and fs (not= fs missing))))
    (catch Exception _ false)))

(defn- resolve-font-location
  []
  (cond
    (not (font-set-usable? ac-normal-id))
    (do
      (log/warn "my_mod:ac_normal unavailable in FontManager; CGui uses default minecraft font")
      nil)

    (bundled-ttf?)
    ac-normal-id

    :else
    ac-normal-id))

(defn- bundled-ttf? []
  (boolean (io/resource "assets/my_mod/font/ac_normal.ttf")))

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
      (let [loc (resolve-font-location)]
        (register-ac-fonts! loc)
        (if loc
          (log/info "AC fonts registered (my_mod:ac_normal)")
          (log/info "AC fonts registered (minecraft default — no usable my_mod:ac_normal)")))
      (catch Exception e
        (log/error "Failed to initialize AC fonts:" (ex-message e))))))
