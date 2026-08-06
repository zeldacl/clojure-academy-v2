(ns cn.li.mc262.gui.reactive.host
  "Standalone reactive screen host -- thin shell over mcbase core.

  26.2: GuiGraphicsExtractor draw path; McAccess.setScreen via gui."
  (:require [cn.li.mcbase.gui.reactive.host-core :as core]
            [cn.li.mc262.gui.reactive.render :as render])
  (:import [cn.li.mc262.shim DelegatingScreen]
           [net.minecraft.network.chat Component]))

(def ^:private seams
  {:new-screen!
   (fn [title render-cb key-cb char-cb click-cb removed-cb]
     (DelegatingScreen. (Component/literal ^String title)
                        render-cb key-cb char-cb click-cb removed-cb))

   :render-background!
   (fn [screen gg mx my pt]
     (.renderBackground screen gg (int mx) (int my) (float pt)))

   :draw-tape! render/draw-tape!
   :render-embedded-runtime! render/render-embedded-runtime!})

(defn create-reactive-screen
  ([rt title] (core/create-reactive-screen* seams rt title))
  ([rt title opts] (core/create-reactive-screen* seams rt title opts)))

(defn open-reactive-screen!
  ([rt title] (core/open-reactive-screen!* seams rt title))
  ([rt title opts] (core/open-reactive-screen!* seams rt title opts)))
