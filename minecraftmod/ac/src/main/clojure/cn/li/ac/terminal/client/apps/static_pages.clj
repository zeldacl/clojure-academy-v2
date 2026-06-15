(ns cn.li.ac.terminal.client.apps.static-pages
  "CLIENT-ONLY: shared helpers for static text pages.  Individual app launch
  functions have moved to dedicated namespaces (about.clj, settings.clj)."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]))

(defn create-text-page-gui
  "Shared helper: build a simple static text page with a background texture,
  title, and line list.  Used internally by apps that need quick read-only
  reference pages."
  [{:keys [title size lines title-font-size line-font-size]}]
  (let [[w h] size
        root (cgui-core/create-widget :size size)
        bg (cgui-core/create-widget :pos [0 0] :size size)
        _ (comp/add-component! bg (comp/draw-texture
                                    (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title-w (cgui-core/create-widget :pos [0 20] :size [w 30])
        _ (comp/add-component! title-w
             (comp/text-box :text title :font :ac-normal
                           :font-size (or title-font-size 12) :color 0xFFFFFFFF))
        content-y 70
        line-h (if (< w 420) 13 15)
        content-widgets (map-indexed
                         (fn [idx line]
                           (let [widget (cgui-core/create-widget
                                         :pos [30 (+ content-y (* idx line-h))]
                                         :size [(- w 60) line-h])]
                             (comp/add-component! widget
                               (comp/text-box :text line :font :ac-normal
                                             :font-size (or line-font-size 8)
                                             :color 0xFFFFFFFF))
                             widget))
                         lines)]
    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title-w)
    (doseq [widget content-widgets]
      (cgui-core/add-widget! root widget))
    root))
