(ns cn.li.mc1201.gui.cgui.input
  "CLIENT-ONLY CGUI input and frame-tick logic."
  (:require [cn.li.mcmod.gui.cgui-widget-model :as cgui-model]
            [cn.li.mcmod.gui.cgui-events :as cgui-events]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mc1201.gui.cgui.traversal :as traversal]
            [cn.li.mcmod.util.log :as log]))

(def ^:private drag-time-tol-ms 100)

(defn frame-tick!
  [root event]
  (when root
    (doseq [[widget _ _] (traversal/collect-widgets-z-ordered root [0 0] 1.0 nil)]
      (try
        (cgui-events/emit-widget-event! widget :frame event)
        (catch Exception _ nil)))
    (let [m @(:metadata root)
          dnode-atom (:dragging-node m)
          last-drag-atom (:last-drag-time m)]
      (when (and dnode-atom @dnode-atom)
        (let [now (System/currentTimeMillis)
              last @last-drag-atom]
          (when (and last (> (- now last) drag-time-tol-ms))
            (try
              (let [dnode @dnode-atom]
                (cgui-events/emit-widget-event! dnode :drag-stop {:time now})
                (reset! dnode-atom nil)
                (reset! last-drag-atom 0))
              (catch Exception _ nil))))))))

(defn mouse-click!
  [root mx my left top button]
  (when-let [hit (traversal/hit-test root mx my left top)]
    (when (== 0 button)
      (cgui-screen/gain-focus! root hit))
    (log/debug "CGUI mouse-click! mx:" mx "my:" my "left:" left "top:" top "button:" button)
    (cgui-events/emit-widget-event!
     hit
     (if (== 0 button) :left-click :right-click)
     {:x mx :y my :button button})))

(defn mouse-drag!
  [root mx my left top]
  (when-let [hit (traversal/hit-test root mx my left top)]
    (let [m @(:metadata root)
          dnode-atom (:dragging-node m)
          last-drag-atom (:last-drag-time m)
          start-atom (:last-start-time m)
          now (System/currentTimeMillis)]
      (when (and dnode-atom (nil? @dnode-atom))
        (reset! dnode-atom hit)
        (reset! start-atom now)
        (cgui-events/emit-widget-event! hit :drag-start {:x mx :y my :time now}))
      (when dnode-atom
        (reset! last-drag-atom now)))
    (cgui-events/emit-widget-event! hit :drag {:x mx :y my})))

(defn key-input!
  [root key-code scan-code typed-char]
  (when root
    (let [m @(:metadata root)
          focus-atom (:cgui-focus m)
          focus (when focus-atom @focus-atom)
          target (or focus root)]
      (cgui-events/emit-widget-event! target :key {:keyCode key-code :scanCode scan-code :typedChar typed-char})
      (when focus
        (when-let [tb (cgui-model/get-widget-component focus :textbox)]
          (let [st (:state tb)]
            (when (and st (:editable? @st))
              (let [enter-keys #{257 335 28}
                    backspace-keys #{259 14}
                    has-char? (and typed-char (not= typed-char (char 0)))
                    curr (str (or (:text @st) ""))]
                (cond
                  (contains? backspace-keys key-code)
                  (when (pos? (count curr))
                    (swap! st assoc :text (subs curr 0 (dec (count curr))))
                    (cgui-events/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  (contains? enter-keys key-code)
                  (cgui-events/emit-widget-event! focus :confirm-input {:value curr})

                  has-char?
                  (do
                    (swap! st assoc :text (str curr typed-char))
                    (cgui-events/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  :else nil)))))))))
