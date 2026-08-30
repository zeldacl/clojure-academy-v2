(ns cn.li.mc262.presentation.screen
  "Minecraft 26.2 Screen boundary for the Presentation Runtime."
  (:require [cn.li.platform.neutral.presentation :as presentation])
  (:import [cn.li.mc262.shim DelegatingScreen]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [net.minecraft.client.gui GuiGraphicsExtractor]))

(defn open! [mount title & [on-close]]
  (let [value (doto (DelegatingScreen.
                      (Component/literal (str title))
                      (fn [s ^GuiGraphicsExtractor graphics mouse-x mouse-y partial-tick]
                        ;; Same as 1.20.1/1.21.1: draw the vanilla dark/blur scrim.
                        ;; Reactive host skips a second extractBackground because its
                        ;; extractRenderState path already did one; Presentation's
                        ;; DelegatingScreen.render replaces that path entirely.
                        (.renderBackground ^DelegatingScreen s graphics
                                           (int mouse-x) (int mouse-y) (float partial-tick))
                        (presentation/submit-current-frame!
                          :screen (float partial-tick) (.-width ^DelegatingScreen s)
                          (.-height ^DelegatingScreen s) graphics))
                      (fn [s key _scan-code _modifiers]
                        (if (= 256 (int key))
                          (do (.onClose ^DelegatingScreen s) true)
                          (do (presentation/dispatch-input!
                                mount {:type :key :key-code (int key) :pressed? true
                                       :shift? false :control? false :alt? false})
                              false)))
                      (fn [_ character _modifiers]
                        (presentation/dispatch-input!
                          mount {:type :character :text (str character) :composing? false})
                        false)
                      (fn [_ mouse-x mouse-y button]
                        (presentation/dispatch-input!
                          mount {:type :pointer :event-type :down
                                 :x mouse-x :y mouse-y :button button})
                        false)
                      (fn [_] nil)
                      (fn [_]
                        (presentation/unmount! mount)
                        (when on-close (on-close))))
                (.withMouseReleased
                  (fn [_ mouse-x mouse-y button]
                    (presentation/dispatch-input!
                      mount {:type :pointer :event-type :up
                             :x mouse-x :y mouse-y :button button})
                    false))
                (.withMouseDragged
                  (fn [_ mouse-x mouse-y button drag-x drag-y]
                    (presentation/dispatch-input!
                      mount {:type :pointer :event-type :drag
                             :x mouse-x :y mouse-y :button button
                             :drag-x drag-x :drag-y drag-y})
                    false))
                (.withMouseMoved
                  (fn [_ mouse-x mouse-y]
                    (presentation/dispatch-input!
                      mount {:type :pointer :event-type :move
                             :x mouse-x :y mouse-y})
                    nil))
                (.withMouseScrolled
                  (fn [_ mouse-x mouse-y delta]
                    (presentation/dispatch-input!
                      mount {:type :scroll :x mouse-x :y mouse-y :delta delta})
                    true)))]
    (.setScreen (.gui (Minecraft/getInstance)) value)
    value))