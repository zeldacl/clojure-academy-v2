(ns cn.li.mc1211.presentation.screen
  "Minecraft 1.21.1 Screen boundary for the Presentation Runtime."
  (:require [cn.li.platform.neutral.presentation :as presentation]
           [cn.li.mc1211.presentation.preview :as preview])
  (:import [cn.li.mc1211.shim DelegatingScreen]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [net.minecraft.client.gui GuiGraphics]))

(defn- consumed? [result]
  (or (= result :consume) (= result :capture-pointer)
      (= (str result) ":consume") (= (str result) ":capture-pointer")))

(defn- capture-pointer? [result]
  (or (= result :capture-pointer)
      (= (str result) ":capture-pointer")))

(defn- as-java-boolean [v]
  (Boolean/valueOf (boolean v)))

(defn open! [mount title & [on-close]]
  (let [scrollbar-drag? (atom false)
        value (doto (DelegatingScreen.
                      (Component/literal (str title))
                      (fn [s ^GuiGraphics graphics mouse-x mouse-y partial-tick]
                        (.renderBackground ^DelegatingScreen s graphics
                                           (int mouse-x) (int mouse-y) (float partial-tick))
                        (when @scrollbar-drag?
                          (presentation/dispatch-input!
                            mount {:type :pointer :event-type :drag
                                   :x mouse-x :y mouse-y :button 0
                                   :drag-x 0.0 :drag-y 0.0}))
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
                          (reset! scrollbar-drag? (capture-pointer? result))
                          (as-java-boolean (consumed? result))))
                      (fn [_] nil)
                      (fn [_]
                        (reset! scrollbar-drag? false)
                        (presentation/unmount! mount)
                        (when on-close (on-close))))
                (.withMouseReleased
                  (fn [_ mouse-x mouse-y button]
                    (reset! scrollbar-drag? false)
                    (as-java-boolean
                      (consumed?
                        (presentation/dispatch-input!
                          mount {:type :pointer :event-type :up
                                 :x mouse-x :y mouse-y :button button})))))
                (.withMouseDragged
                  (fn [_ mouse-x mouse-y button drag-x drag-y]
                    (let [result (presentation/dispatch-input!
                                   mount {:type :pointer :event-type :drag
                                          :x mouse-x :y mouse-y :button button
                                          :drag-x drag-x :drag-y drag-y})]
                      (when (capture-pointer? result)
                        (reset! scrollbar-drag? true))
                      (as-java-boolean (consumed? result)))))
                (.withMouseMoved
                  (fn [_ mouse-x mouse-y]
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
                    (as-java-boolean true))))]
    (.setScreen (Minecraft/getInstance) value)
    value))
