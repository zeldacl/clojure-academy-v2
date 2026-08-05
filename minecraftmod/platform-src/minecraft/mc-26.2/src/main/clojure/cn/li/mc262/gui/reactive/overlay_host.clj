(ns cn.li.mc262.gui.reactive.overlay-host
  "Overlay host — manages a persistent UiRt per client session.
   No Screen, no input. One runtime per client-session owner.

   26.2: update-overlay! drives layout/clock and render/draw-tape!
   (GuiGraphicsExtractor). Text uses vanilla Font via cgui-font/draw-text!."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc262.gui.reactive.render :as render]
            [cn.li.mc262.gui.reactive.clock :as clock])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [java.util HashMap]))

(def ^:private ^HashMap overlay-runtimes (HashMap.))

(defn register-overlay!
  "Register a reactive overlay runtime for a client session.
   build-fn: (fn [screen-w screen-h] -> UiRt) creates the tree + bindings."
  [client-session-id build-fn screen-w screen-h]
  (let [rt (build-fn screen-w screen-h)]
    (rt/resize! rt (double screen-w) (double screen-h))
    (.put overlay-runtimes client-session-id
          {:runtime rt :build-fn build-fn :width screen-w :height screen-h})
    rt))

(defn get-overlay-runtime
  "Get or create the overlay runtime for a client session."
  [client-session-id screen-w screen-h]
  (if-let [entry (.get overlay-runtimes client-session-id)]
    (:runtime entry)
    nil))

(defn update-overlay!
  "Per-frame update: lazy get-or-create runtime, sset clock signals,
   flush bindings, layout, render. Called from Forge/Fabric render events.
   gg is the platform graphics handle (was GuiGraphics on 1.21.1)."
  [gg client-session-id screen-w screen-h partial-ticks build-fn update-fn]
  (let [entry (.get overlay-runtimes client-session-id)
        resized? (and entry
                      (or (not= screen-w (:width entry))
                          (not= screen-h (:height entry))))
        _ (when resized?
            (rt/dispose! (:runtime entry))
            (.remove overlay-runtimes client-session-id))
        ^UiRt runtime (when (and (or (nil? entry) resized?) build-fn)
                        (when-let [new-runtime (build-fn screen-w screen-h)]
                          (rt/resize! new-runtime (double screen-w) (double screen-h))
                          (.put overlay-runtimes client-session-id
                                {:runtime new-runtime
                                 :width screen-w
                                 :height screen-h})
                          new-runtime))
        ^UiRt rt (or runtime (when-not resized? (:runtime entry)))]
    (when rt
      (clock/tick! rt partial-ticks)
      (when update-fn (update-fn rt))
      (rt/flush! rt)
      (layout/ensure-layout! rt)
      (layout/ensure-tape! rt)
      (render/draw-tape! gg rt 0 0))))

(defn dispose-overlay!
  "Clean up overlay runtime for a client session."
  [client-session-id]
  (when-let [entry (.remove overlay-runtimes client-session-id)]
    (rt/dispose! (:runtime entry))
    nil))
