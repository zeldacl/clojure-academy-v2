(ns cn.li.mc1201.gui.cgui.runtime
  "CLIENT-ONLY runtime facade for CGUI rendering and event dispatch."
  (:require [cn.li.mc1201.gui.cgui.input :as input]
            [cn.li.mc1201.gui.cgui.renderer :as renderer]))

(defn resize-root!
  [root width height]
  (renderer/resize-root! root width height))

(defn frame-tick!
  [root event]
  (input/frame-tick! root event))

(defn render-tree!
  [gg root left top]
  (renderer/render-tree! gg root left top))

(defn mouse-click!
  [root mx my left top button]
  (input/mouse-click! root mx my left top button))

(defn mouse-drag!
  [root mx my left top]
  (input/mouse-drag! root mx my left top))

(defn key-input!
  [root key-code scan-code typed-char]
  (input/key-input! root key-code scan-code typed-char))

(defn dispose!
  [_root]
  nil)

(defn focused-editable-textbox?
  [root]
  (input/focused-editable-textbox? root))

(defn focused-widget-owns-key?
  [root]
  (input/focused-widget-owns-key? root))
