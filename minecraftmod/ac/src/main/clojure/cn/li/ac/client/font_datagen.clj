(ns cn.li.ac.client.font-datagen
  "AC client font json for datagen (official FontManager providers).

  See https://minecraft.wiki/w/Font

  The baseline definition references minecraft:default only.  At runtime a
  virtual resource pack (SystemFontVirtualPack) injects a ttf provider with
  calibrated parameters when a system TrueType font is detected — zero font
  files are bundled in the mod JAR.

  When no system font is available the minecraft:default bitmap font is used
  as a transparent fallback."
  (:require [cn.li.mcmod.datagen.metadata :as datagen-metadata]))

(defn- ac-normal-providers
  []
  [{:type "reference"
    :id "minecraft:default"}])

(def baseline-font-definitions
  [{:id "ac_normal"
    :providers (ac-normal-providers)}])

(defn register-datagen-metadata!
  []
  (datagen-metadata/set-fonts! baseline-font-definitions)
  nil)
