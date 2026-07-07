(ns cn.li.mc1201.gui.reactive.host-container
  "Reactive container screen host — wraps DelegatingCGuiContainerScreen with UiRt.
   Same pattern as host.clj but for container-backed screens."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mc1201.gui.reactive.render :as render]
            [cn.li.mc1201.gui.reactive.clock :as clock]
            [cn.li.mc1201.gui.reactive.input :as input])
  (:import [cn.li.mcmod.ui.runtime UiRt]
           [cn.li.mc1201.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.world.entity.player Inventory]
           [net.minecraft.world.inventory AbstractContainerMenu]
           [net.minecraft.network.chat Component]))

(defn create-reactive-container-screen
  "Build a DelegatingCGuiContainerScreen hosting a reactive UiRt.
   menu: Minecraft AbstractContainerMenu
   player-inv: Inventory
   title: screen title string"
  [^UiRt rt ^AbstractContainerMenu menu ^Inventory player-inv title]
  (doto (DelegatingCGuiContainerScreen. menu player-inv (Component/literal ^String title))
    (.withRender
      (fn render-cb [^DelegatingCGuiContainerScreen this ^GuiGraphics gg mx my pt]
        (try
          (.renderBackground this gg)
          (clock/tick! rt pt)
          (rt/resize! rt (double (.-width this)) (double (.-height this)))
          (rt/flush! rt)
          (layout/ensure-layout! rt)
          (layout/ensure-tape! rt)
          (render/draw-tape! gg rt (.-leftPos this) (.-topPos this))
          ;; Render vanilla slots on top
          (.callSuperRender this gg mx my pt)
          (catch Exception e
            (.printStackTrace e)))))
    (.withRenderBg
      (fn bg-cb [^DelegatingCGuiContainerScreen this ^GuiGraphics gg _mx _my _pt]
        (.callSuperRenderBackground this gg)))
    (.withMouseClicked
      (fn click-cb [^DelegatingCGuiContainerScreen this mx my button]
        (input/handle-mouse-clicked rt (.-leftPos this) (.-topPos this) mx my button)))
    (.withMouseReleased
      (fn release-cb [^DelegatingCGuiContainerScreen this mx my button]
        (input/handle-mouse-released rt (.-leftPos this) (.-topPos this) mx my button)))
    (.withKeyPressed
      (fn key-cb [_this key-code scan-code modifiers]
        (input/handle-key-pressed rt key-code scan-code modifiers)))
    (.withCharTyped
      (fn char-cb [_this code-point modifiers]
        (input/handle-char-typed rt code-point modifiers)))
    (.withRemoved
      (fn removed-cb [_this]
        (input/handle-removed rt)))))

(defn create-tech-ui-container-screen
  "Create container screen from reactive tech-ui assembled map.
   {:runtime :root :container :minecraft-container :size-dx :size-dy}"
  [{:keys [runtime minecraft-container size-dx size-dy]}]
  (let [^DelegatingCGuiContainerScreen screen
        (create-reactive-container-screen
          runtime
          (.-menu minecraft-container)
          (.-playerInventory minecraft-container)
          (.getTitle minecraft-container))]
    (when size-dx (set! (.-imageWidth screen) (+ 176 (int size-dx))))
    (when size-dy (set! (.-imageHeight screen) (+ 166 (int size-dy))))
    screen))
