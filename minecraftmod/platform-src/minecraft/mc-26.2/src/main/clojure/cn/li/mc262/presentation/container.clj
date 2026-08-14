(ns cn.li.mc262.presentation.container
  "Minecraft 26.2 Presentation Container boundary."
  (:require [cn.li.mcbase.gui.screen.impl :as screen-impl]
            [cn.li.platform.neutral.presentation :as presentation])
  (:import [cn.li.mc262.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphicsExtractor]
           [net.minecraft.network.chat Component]))

(defn- input! [mount event] (presentation/dispatch-input! mount event))
(defn- consumed? [result]
  (or (= result :consume) (= result :capture-pointer)))

(defn create! [data]
  (let [{:keys [mount on-close]} ((:mount-fn data) data)
        ^DelegatingCGuiContainerScreen screen
        (DelegatingCGuiContainerScreen.
          (:minecraft-container data) (:player-inventory data)
          (Component/literal (str (:screen-title data "Container"))))]
    (doto screen
      (.withRender
        (fn [s ^GuiGraphicsExtractor graphics mouse-x mouse-y partial-tick]
          (.callSuperRender ^DelegatingCGuiContainerScreen s graphics
                            (int mouse-x) (int mouse-y) (float partial-tick))
          (presentation/submit-current-frame!
            :screen (float partial-tick) (.-width ^DelegatingCGuiContainerScreen s)
            (.-height ^DelegatingCGuiContainerScreen s) graphics)))
      (.withMouseClicked (fn [s x y button]
                           (if (consumed? (input! mount {:type :pointer :event-type :down :x x :y y :button button})) true
                               (.callSuperMouseClicked ^DelegatingCGuiContainerScreen s x y button))))
      (.withMouseReleased (fn [s x y button]
                            (if (consumed? (input! mount {:type :pointer :event-type :up :x x :y y :button button})) true
                                (.callSuperMouseReleased ^DelegatingCGuiContainerScreen s x y button))))
      (.withMouseMoved (fn [s x y]
                         (input! mount {:type :pointer :event-type :move :x x :y y :button -1})
                         (.callSuperMouseMoved ^DelegatingCGuiContainerScreen s x y)))
      (.withMouseScrolled (fn [s x y delta]
                            (if (consumed? (input! mount {:type :scroll :x x :y y :delta delta})) true
                                (.callSuperMouseScrolled ^DelegatingCGuiContainerScreen s x y delta))))
      (.withKeyPressed (fn [s key scan modifiers]
                         (if (= 256 (int key))
                           (do (.onClose ^DelegatingCGuiContainerScreen s) true)
                           (if (consumed? (input! mount {:type :key :key-code (int key) :pressed? true :scan-code (int scan) :modifiers (int modifiers)})) true
                               (.callSuperKeyPressed ^DelegatingCGuiContainerScreen s (int key) (int scan) (int modifiers))))))
      (.withCharTyped (fn [s character modifiers]
                        (if (consumed? (input! mount {:type :character :text (str (char character)) :modifiers (int modifiers) :composing? false})) true
                            (.callSuperCharTyped ^DelegatingCGuiContainerScreen s (char character) (int modifiers)))))
      (.withRemoved (fn [_] (presentation/unmount! mount) (when on-close (on-close)))))
    screen))

(screen-impl/install-create-presentation-container-screen! create!)
