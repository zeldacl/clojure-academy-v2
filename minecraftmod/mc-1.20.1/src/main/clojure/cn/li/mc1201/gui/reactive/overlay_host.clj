(ns cn.li.mc1201.gui.reactive.overlay-host
  "Overlay host — manages a persistent UiRt per client session.
   No Screen, no input. One runtime per client-session owner.
   Replaces :client-build-overlay-plan with signal-driven updates."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock])
  (:import [cn.li.mcmod.ui.runtime UiRt]
           [net.minecraft.client.gui GuiGraphics]))

(def ^:private overlay-runtimes (atom {}))
;; {:client-session-id {:runtime rt :dispose-fn fn}}

(defn register-overlay!
  "Register a reactive overlay runtime for a client session.
   build-fn: (fn [screen-w screen-h] -> UiRt) creates the tree + bindings."
  [client-session-id build-fn screen-w screen-h]
  (let [rt (build-fn screen-w screen-h)]
    (swap! overlay-runtimes assoc client-session-id {:runtime rt :build-fn build-fn})
    rt))

(defn get-overlay-runtime
  "Get or create the overlay runtime for a client session."
  [client-session-id screen-w screen-h]
  (if-let [entry (get @overlay-runtimes client-session-id)]
    (:runtime entry)
    nil))

(defn update-overlay!
  "Per-frame update: sset clock signals, flush bindings, layout, render.
   Called from Forge/Fabric render event handlers."
  [^GuiGraphics gg client-session-id screen-w screen-h partial-ticks update-fn]
  (when-let [entry (get @overlay-runtimes client-session-id)]
    (let [^UiRt rt (:runtime entry)]
      (clock/tick! rt partial-ticks)
      (rt/resize! rt (double screen-w) (double screen-h))
      ;; Call ac-level update to sset game-state signals
      (when update-fn (update-fn rt))
      (rt/flush! rt)
      (layout/ensure-layout! rt)
      (layout/ensure-tape! rt)
      (render/draw-tape! gg rt 0 0))))

(defn dispose-overlay!
  "Clean up overlay runtime for a client session."
  [client-session-id]
  (when-let [entry (get @overlay-runtimes client-session-id)]
    (rt/dispose! (:runtime entry))
    (swap! overlay-runtimes dissoc client-session-id)
    nil))
