(ns cn.li.mc1201.gui.reactive.input
  "Input wiring: DelegatingScreen callbacks -> event dispatch."
  (:require [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.layout :as layout])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]))

(defn handle-key-pressed
  "Dispatch key to focus node.
   Returns true only when an editable-text or focused node consumes the key.
   Returns false otherwise — this lets Minecraft's default Screen.keyPressed
   handle ESC (close screen), F3, etc.  Previously ESC returned true which
   told MC the key was consumed and the screen stayed open."
  [^UiRt rt key-code scan-code modifiers]
  (if (events/dispatch-editable-key! rt key-code (char 0))
    true
    (do
      (events/dispatch-key! rt key-code scan-code modifiers 0)
      (>= (cn.li.mcmod.ui.runtime/focus-idx rt) 0))))

(defn handle-char-typed [^UiRt rt code-point _modifiers]
  (if (events/dispatch-editable-key! rt 0 (char code-point))
    true
    (do
      (events/dispatch-char! rt code-point)
      true)))

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

(defn handle-mouse-moved
  "Track hovered node: update hovered-idx + FLAG-HOVERED on nodes so
   :box hover-tint renders without any per-frame polling."
  [^UiRt rt left top mouse-x mouse-y]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))
        ^cn.li.mcmod.ui.node.INode hit (layout/hit-test rt mx my)
        new-idx (if hit (.getIdx hit) -1)
        old-idx (cn.li.mcmod.ui.runtime/hovered-idx rt)]
    (when (not= new-idx old-idx)
      ;; Clear old hover flag
      (when (>= old-idx 0)
        (when-let [^cn.li.mcmod.ui.node.INode old-node
                   (cn.li.mcmod.ui.runtime/node-by-idx rt old-idx)]
          (.clearFlag old-node cn.li.mcmod.ui.node/FLAG-HOVERED)))
      ;; Set new hover flag
      (when hit
        (.setFlag hit cn.li.mcmod.ui.node/FLAG-HOVERED))
      (cn.li.mcmod.ui.runtime/set-hovered-idx! rt new-idx))
    (when-let [on-move (cn.li.mcmod.ui.runtime/user-signal rt :on-pointer-move)]
      (on-move mx my))
    false))

(defn handle-mouse-scrolled [^UiRt rt left top mouse-x mouse-y scroll-delta]
  (let [mx (- (double mouse-x) (double left))
        my (- (double mouse-y) (double top))]
    (events/dispatch-scroll! rt mx my scroll-delta)
    true))

(defn handle-removed [^UiRt rt]
  (cn.li.mcmod.ui.runtime/dispose! rt))
