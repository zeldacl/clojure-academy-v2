(ns cn.li.mc262.gui.reactive.host-container
  "Reactive container screen host -- thin shell over mcbase core.

  26.2: image size is constructor-final; graphics handle is GuiGraphicsExtractor."
  (:require [cn.li.mcbase.gui.reactive.host-container-core :as core]
            [cn.li.mc262.gui.reactive.render :as render])
  (:import [cn.li.mc262.shim DelegatingCGuiContainerScreen]
           [net.minecraft.network.chat Component]))

(def ^:private seams
  {:new-screen!
   (fn [menu inv title iw ih]
     (DelegatingCGuiContainerScreen. menu inv (Component/literal ^String title)
                                     (int iw) (int ih)))

   :render-background!
   (fn [screen gg mx my pt]
     (.renderBackground screen gg (int mx) (int my) (float pt)))

   :draw-tape! render/draw-tape!
   :render-embedded-runtime! render/render-embedded-runtime!})

(def create-reactive-container-screen
  (partial core/create-reactive-container-screen* seams))

(def create-tech-ui-container-screen
  (partial core/create-tech-ui-container-screen* seams))

(core/install! seams)
