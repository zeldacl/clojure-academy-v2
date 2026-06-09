(ns cn.li.ac.client.font-datagen
  "AC client font json for datagen (official FontManager providers).

  See https://minecraft.wiki/w/Font

  Important: a ttf provider with a missing file causes FontManager to reject
  my_mod:ac_normal entirely — the reference provider after it will NOT apply.
  Only emit ttf when assets/my_mod/font/ac_normal.ttf is actually bundled."
  (:require [clojure.java.io :as io]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata]))

(defn- bundled-ttf?
  []
  (boolean (io/resource "assets/my_mod/font/ac_normal.ttf")))

(defn- ac-normal-providers
  []
  (if (bundled-ttf?)
    [{:type "ttf"
      :file "my_mod:ac_normal.ttf"
      :size 11.0
      :oversample 2.0
      :shift [0 0]}
     {:type "reference"
      :id "minecraft:default"}]
    [{:type "reference"
      :id "minecraft:default"}]))

(def baseline-font-definitions
  [{:id "ac_normal"
    :providers (ac-normal-providers)}])

(defn register-datagen-metadata!
  []
  (datagen-metadata/set-fonts! baseline-font-definitions)
  nil)
