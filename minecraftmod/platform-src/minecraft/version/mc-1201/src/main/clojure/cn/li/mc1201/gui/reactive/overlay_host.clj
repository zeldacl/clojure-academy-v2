(ns cn.li.mc1201.gui.reactive.overlay-host
  "Overlay host — manages a persistent UiRt per client session.
   No Screen, no input. One runtime per client-session owner.
   Replaces :client-build-overlay-plan with signal-driven updates."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [java.util HashMap]
           [net.minecraft.client.gui GuiGraphics]))

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
   build-fn: (fn [screen-w screen-h] -> UiRt) — used once when no runtime exists."
  [^GuiGraphics gg client-session-id screen-w screen-h partial-ticks build-fn update-fn]
  (let [entry (.get overlay-runtimes client-session-id)
        resized? (and entry
                      (or (not= screen-w (:width entry))
                          (not= screen-h (:height entry))))
        _ (when resized?
            (rt/dispose! (:runtime entry))
            (.remove overlay-runtimes client-session-id))
        ^UiRt runtime (when (and (or (nil? entry) resized?) build-fn)
                        (when-let [new-runtime (build-fn screen-w screen-h)]
                          ;; Screens keep UiRt screen size fresh via per-frame
                          ;; rt/resize!; the overlay bakes sizes at build time,
                          ;; so initialize it here — otherwise rt/screen-w
                          ;; reads 0 and every consumer (snapshot geometry,
                          ;; apply-screen-size!) computes for a 0×0 screen and
                          ;; the HUD stays invisible.
                          (rt/resize! new-runtime (double screen-w) (double screen-h))
                          (.put overlay-runtimes client-session-id
                                {:runtime new-runtime
                                 :width screen-w
                                 :height screen-h})
                          new-runtime))
        ^UiRt rt (or runtime (when-not resized? (:runtime entry)))]
    (when rt
      (clock/tick! rt partial-ticks)
      ;; Call ac-level update to sset game-state signals
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
