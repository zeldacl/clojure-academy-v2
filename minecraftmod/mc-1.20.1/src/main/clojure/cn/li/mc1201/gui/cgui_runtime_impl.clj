(ns cn.li.mc1201.gui.cgui-runtime-impl
  "CLIENT-ONLY: shared 1.20.1 CGUI runtime for GUI rendering and event dispatch.

  This namespace contains client-side GUI rendering code and must only be loaded
  when a GUI screen is opened (client-only operation). It drives rendering and
  input for the pure Clojure widget tree."
  (:require [cn.li.mc1201.gui.cgui-input-core :as input-core]
            [cn.li.mc1201.gui.cgui-render-core :as render-core]
            [cn.li.mc1201.gui.cgui-runtime :as runtime]))

(defn dispose!
  [_root]
  nil)

(runtime/register-impl!
  {:resize-root! render-core/resize-root!
   :frame-tick! input-core/frame-tick!
   :render-tree! render-core/render-tree!
   :mouse-click! input-core/mouse-click!
   :mouse-drag! input-core/mouse-drag!
   :key-input! input-core/key-input!
   :dispose! dispose!})
