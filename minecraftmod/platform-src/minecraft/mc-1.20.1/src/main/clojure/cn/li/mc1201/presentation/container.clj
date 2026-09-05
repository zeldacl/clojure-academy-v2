(ns cn.li.mc1201.presentation.container
  "Minecraft 1.20.1 Presentation Container boundary."
  (:require [cn.li.mcbase.gui.screen.impl :as screen-impl]
            [cn.li.platform.neutral.presentation :as presentation]
           [cn.li.mc1201.presentation.preview :as preview])
  (:import [cn.li.mc1201.shim DelegatingCGuiContainerScreen]
           [net.minecraft.client.gui GuiGraphics]
           [net.minecraft.network.chat Component]))

(defn- input! [mount event] (presentation/dispatch-input! mount event))
(defn- consumed? [result]
  (or (= result :consume) (= result :capture-pointer)
      (= (str result) ":consume") (= (str result) ":capture-pointer")))
(defn- as-java-boolean [v]
  (Boolean/valueOf (boolean v)))

(defn create! [data]
  (let [{:keys [mount on-close]} ((:mount-fn data) data)
        image-w (int (or (:image-width data) 290))
        image-h (int (or (:image-height data) 187))
        ^DelegatingCGuiContainerScreen screen
        (DelegatingCGuiContainerScreen.
          (:minecraft-container data) (:player-inventory data)
          (Component/literal (str (:screen-title data "Container"))))]
    (.setImageSize screen image-w image-h)
    (doto screen
      (.withRender
        (fn [s ^GuiGraphics graphics mouse-x mouse-y partial-tick]
          (.callSuperRender ^DelegatingCGuiContainerScreen s graphics
                            (int mouse-x) (int mouse-y) (float partial-tick))
          (presentation/submit-current-frame!
            :screen (float partial-tick) (.-width ^DelegatingCGuiContainerScreen s)
            (.-height ^DelegatingCGuiContainerScreen s) (merge {:graphics graphics} (preview/backend-context)))))
      (.withMouseClicked
        (fn [s x y button]
          (as-java-boolean
            (if (consumed? (input! mount {:type :pointer :event-type :down
                                          :space :viewport :x x :y y :button button}))
              true
              (.callSuperMouseClicked ^DelegatingCGuiContainerScreen s x y button)))))
      (.withMouseReleased
        (fn [s x y button]
          (as-java-boolean
            (if (consumed? (input! mount {:type :pointer :event-type :up
                                          :space :viewport :x x :y y :button button}))
              true
              (.callSuperMouseReleased ^DelegatingCGuiContainerScreen s x y button)))))
      (.withMouseMoved
        (fn [s x y]
          (input! mount {:type :pointer :event-type :move
                         :space :viewport :x x :y y :button -1})
          (.callSuperMouseMoved ^DelegatingCGuiContainerScreen s x y)))
      (.withMouseScrolled
        (fn [s x y delta]
          (as-java-boolean
            (if (consumed? (input! mount {:type :scroll :space :viewport :x x :y y :delta delta}))
              true
              (.callSuperMouseScrolled ^DelegatingCGuiContainerScreen s x y delta)))))
      (.withKeyPressed
        (fn [s key scan modifiers]
          (as-java-boolean
            (cond
              (= 256 (int key))
              (do (.onClose ^DelegatingCGuiContainerScreen s) true)
              ;; Only claim editing keys when Presentation has a text focus;
              ;; otherwise let the container handle hotbar / slot shortcuts.
              (consumed? (input! mount {:type :key :key-code (int key) :pressed? true
                                        :scan-code (int scan) :modifiers (int modifiers)}))
              true
              :else
              (.callSuperKeyPressed ^DelegatingCGuiContainerScreen s
                                    (int key) (int scan) (int modifiers))))))
      (.withCharTyped
        (fn [s character modifiers]
          (as-java-boolean
            (if (consumed? (input! mount {:type :character
                                          :text (str (char character))
                                          :modifiers (int modifiers) :composing? false}))
              true
              (.callSuperCharTyped ^DelegatingCGuiContainerScreen s
                                   (char character) (int modifiers))))))
      (.withRemoved
        (fn [_]
          (presentation/unmount! mount)
          (when on-close (on-close)))))
    screen))

(screen-impl/install-create-presentation-container-screen! create!)
