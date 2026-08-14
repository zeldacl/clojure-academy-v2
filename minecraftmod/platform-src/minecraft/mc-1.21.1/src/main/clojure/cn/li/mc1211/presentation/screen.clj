(ns cn.li.mc1211.presentation.screen
  "Minecraft 1.21.1 Screen boundary for the Presentation Runtime."
  (:require [cn.li.platform.neutral.presentation :as presentation])
  (:import [cn.li.mc1211.shim DelegatingScreen]
           [net.minecraft.client Minecraft]
           [net.minecraft.network.chat Component]
           [net.minecraft.client.gui GuiGraphics]))

(defn open! [mount title & [on-close]]
  (let [value (DelegatingScreen.
                (Component/literal (str title))
                (fn [s ^GuiGraphics graphics _mouse-x _mouse-y partial-tick]
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
                (fn [_]
                  (presentation/unmount! mount)
                  (when on-close (on-close))))]
    (.setScreen (Minecraft/getInstance) value)
    value))
