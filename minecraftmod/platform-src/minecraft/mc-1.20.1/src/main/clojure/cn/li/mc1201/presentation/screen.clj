(ns cn.li.mc1201.presentation.screen
  "Minecraft 1.20.1 Screen boundary for the Presentation Runtime."
  (:require [cn.li.platform.neutral.presentation :as presentation]
           [cn.li.mc1201.presentation.preview :as preview])
  (:import [cn.li.mc1201.shim DelegatingScreen]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [net.minecraft.client.gui GuiGraphics]))

(defn- consumed? [result]
  (or (= result :consume) (= result :capture-pointer)))

(defn open! [mount title & [on-close]]
  ;; Latch only on scrollbar capture (:capture-pointer). General :consume from
  ;; the app reducer must not turn every click into a drag stream.
  (let [scrollbar-drag? (atom false)
        value (doto (DelegatingScreen.
                      (Component/literal (str title))
                      (fn [s ^GuiGraphics graphics mouse-x mouse-y partial-tick]
                        (.renderBackground ^DelegatingScreen s graphics)
                        (presentation/submit-current-frame!
                          :screen (float partial-tick) (.-width ^DelegatingScreen s)
                          (.-height ^DelegatingScreen s) (merge {:graphics graphics} (preview/backend-context))))
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
                        (let [result (presentation/dispatch-input!
                                       mount {:type :pointer :event-type :down
                                              :x mouse-x :y mouse-y :button button})]
                          (reset! scrollbar-drag? (= result :capture-pointer))
                          (consumed? result)))
                      (fn [_] nil)
                      (fn [_]
                        (reset! scrollbar-drag? false)
                        (presentation/unmount! mount)
                        (when on-close (on-close))))
                (.withMouseReleased
                  (fn [_ mouse-x mouse-y button]
                    (reset! scrollbar-drag? false)
                    (consumed?
                      (presentation/dispatch-input!
                        mount {:type :pointer :event-type :up
                               :x mouse-x :y mouse-y :button button}))))
                (.withMouseDragged
                  (fn [_ mouse-x mouse-y button drag-x drag-y]
                    (consumed?
                      (presentation/dispatch-input!
                        mount {:type :pointer :event-type :drag
                               :x mouse-x :y mouse-y :button button
                               :drag-x drag-x :drag-y drag-y}))))
                (.withMouseMoved
                  (fn [_ mouse-x mouse-y]
                    ;; Fallback when the host never delivers mouseDragged.
                    (when @scrollbar-drag?
                      (presentation/dispatch-input!
                        mount {:type :pointer :event-type :drag
                               :x mouse-x :y mouse-y :button 0
                               :drag-x 0.0 :drag-y 0.0}))
                    (presentation/dispatch-input!
                      mount {:type :pointer :event-type :move
                             :x mouse-x :y mouse-y})
                    nil))
                (.withMouseScrolled
                  (fn [_ mouse-x mouse-y delta]
                    (presentation/dispatch-input!
                      mount {:type :scroll :x mouse-x :y mouse-y :delta delta})
                    true)))]
    (.setScreen (Minecraft/getInstance) value)
    value))
