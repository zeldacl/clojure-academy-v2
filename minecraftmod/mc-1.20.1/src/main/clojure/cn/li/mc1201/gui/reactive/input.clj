(ns cn.li.mc1201.gui.reactive.input
  "Input wiring: DelegatingScreen callbacks -> event dispatch."
  (:require [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.layout :as layout])
  (:import [cn.li.mcmod.ui.runtime UiRt]))

(defn handle-key-pressed [^UiRt rt key-code scan-code modifiers]
  (events/dispatch-key! rt key-code scan-code modifiers 0)
  true)

(defn handle-char-typed [^UiRt rt code-point _modifiers]
  (events/dispatch-char! rt code-point)
  true)

(defn handle-mouse-clicked [^UiRt rt left top mouse-x mouse-y button]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (events/dispatch-mouse-press! rt mx my button)
    true))

(defn handle-mouse-released [^UiRt rt left top mouse-x mouse-y button]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (events/dispatch-mouse-release! rt mx my button)
    true))

(defn handle-mouse-dragged [^UiRt rt left top mouse-x mouse-y button _drag-x _drag-y]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (events/dispatch-mouse-drag! rt mx my button)
    true))

(defn handle-mouse-moved [^UiRt rt left top mouse-x mouse-y]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (cn.li.mcmod.ui.runtime/set-hovered-idx! rt
      (if-let [^cn.li.mcmod.ui.node.INode hit (layout/hit-test rt mx my)]
        (.getIdx hit) -1))
    false))

(defn handle-mouse-scrolled [^UiRt rt left top mouse-x mouse-y scroll-delta]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (events/dispatch-scroll! rt mx my scroll-delta)
    true))

(defn handle-removed [^UiRt rt]
  (cn.li.mcmod.ui.runtime/dispose! rt))
