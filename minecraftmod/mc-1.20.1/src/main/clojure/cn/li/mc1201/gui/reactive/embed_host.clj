(ns cn.li.mc1201.gui.reactive.embed-host
  "Embed a UiRt inside a CGUI widget tree (developer panel skill tree, overlays)."
  (:require [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [net.minecraft.client.gui GuiGraphics]))

(defn render-embedded!
  [^GuiGraphics gg ^UiRt rt x y w h partial-ticks]
  (render/render-embedded-runtime! gg rt x y w h partial-ticks))

(defn reactive-embed-host!
  "Attach reactive UiRt renderer into CGUI parent widget. Returns host widget."
  [parent ^UiRt rt]
  (let [[pw ph] (cgui-core/get-size parent)
        widget (cgui-core/create-widget :pos [0 0] :size [pw ph])]
    (comp/add-component! widget (comp/reactive-embed {:rt rt}))
    (cgui-core/add-widget! parent widget)
    widget))
