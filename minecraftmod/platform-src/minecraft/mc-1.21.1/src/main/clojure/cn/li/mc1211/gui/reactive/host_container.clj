(ns cn.li.mc1211.gui.reactive.host-container
  "Reactive container screen host -- thin shell over mcbase core."
  (:require [cn.li.mcbase.gui.reactive.host-container-core :as core]
            [cn.li.mc1211.gui.reactive.render :as render])
  (:import [cn.li.mc1211.shim DelegatingCGuiContainerScreen]
           [net.minecraft.network.chat Component]))

(def ^:private seams
  {:new-screen!
   (fn [menu inv title _iw _ih]
     (DelegatingCGuiContainerScreen. menu inv (Component/literal ^String title)))

   :render-background!
   (fn [screen gg mx my pt]
     (.renderBackground screen gg (int mx) (int my) (float pt)))

   :draw-tape! render/draw-tape!
   :render-embedded-runtime! render/render-embedded-runtime!

   :apply-image-size!
   (fn [screen iw ih]
     (.setImageSize screen (int iw) (int ih)))})

(def create-reactive-container-screen
  (partial core/create-reactive-container-screen* seams))

(def create-tech-ui-container-screen
  (partial core/create-tech-ui-container-screen* seams))

(core/install! seams)
