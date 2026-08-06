(ns cn.li.mc1201.gui.reactive.host
  "Standalone reactive screen host -- thin shell over mcbase core."
  (:require [cn.li.mcbase.gui.reactive.host-core :as core]
            [cn.li.mc1201.gui.reactive.render :as render])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.network.chat Component]))

(def ^:private seams
  {:new-screen!
   (fn [title render-cb key-cb char-cb click-cb removed-cb]
     (DelegatingScreen. (Component/literal ^String title)
                        render-cb key-cb char-cb click-cb removed-cb))

   :render-background!
   (fn [screen gg _mx _my _pt]
     (.renderBackground screen gg))

   :draw-tape! render/draw-tape!
   :render-embedded-runtime! render/render-embedded-runtime!})

(defn create-reactive-screen
  ([rt title] (core/create-reactive-screen* seams rt title))
  ([rt title opts] (core/create-reactive-screen* seams rt title opts)))

(defn open-reactive-screen!
  ([rt title] (core/open-reactive-screen!* seams rt title))
  ([rt title opts] (core/open-reactive-screen!* seams rt title opts)))
