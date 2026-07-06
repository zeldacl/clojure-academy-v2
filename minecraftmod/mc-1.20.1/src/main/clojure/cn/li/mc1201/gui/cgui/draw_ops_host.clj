(ns cn.li.mc1201.gui.cgui.draw-ops-host
  "CGUI widget that hosts draw-ops rendering inside the CGUI component system.

  Rendering is delegated to the shared draw-ops engine (cn.li.mc1201.gui.draw-ops).
  This namespace provides the CGUI-specific wrapper: enableBlend preset + widget factory.

  Usage:
    (draw-ops-host! parent-area ops-fn)
  where ops-fn is a (fn [] ops-vector) that produces draw ops each frame."
  (:require [cn.li.mc1201.gui.draw-ops :as draw-ops]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp])
  (:import [com.mojang.blaze3d.systems RenderSystem]
           [net.minecraft.client.gui GuiGraphics]))

;; ============================================================================
;; Draw-ops renderer — CGUI wrapper with enableBlend preset
;; ============================================================================

(defn render-ops!
  "Render a vector of draw ops into the given GuiGraphics.
   Enables blend before dispatching to shared engine (CGUI widget context).
   Called by the CGUI renderer each frame for :draw-ops components."
  [^GuiGraphics graphics ops]
  (RenderSystem/enableBlend)
  (draw-ops/render-ops! graphics ops))

;; ============================================================================
;; DrawOpsWidget factory
;; ============================================================================

(defn draw-ops-host!
  "Attach a draw-ops rendering widget into `parent`.
   ops-fn is (fn [] ops-vector) called each render frame to produce draw ops.
   Returns the host widget."
  [parent ops-fn]
  (let [[pw ph] (cgui-core/get-size parent)
        widget (cgui-core/create-widget :pos [0 0] :size [pw ph])]
    (comp/add-component! widget (comp/draw-ops {:ops-fn ops-fn}))
    (cgui-core/add-widget! parent widget)
    widget))
