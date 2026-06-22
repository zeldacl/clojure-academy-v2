(ns cn.li.mc1201.gui.cgui.input
  "CLIENT-ONLY CGUI input and frame-tick logic."
  (:require [cn.li.mcmod.gui.components :as components]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mc1201.gui.cgui.traversal :as traversal]
            [cn.li.mcmod.util.log :as log]))

(def ^:private drag-time-tol-ms 100)

(defn frame-tick!
  [root event]
  (when root
    (try
      (doseq [[widget _ _] (traversal/collect-widgets-z-ordered root [0 0] 1.0 nil)]
        (try
          (events/emit-widget-event! widget :frame event)
          (catch Exception _ nil)))
      (catch Exception e
        (log/error "[FRAME-TICK-WALK] " (.getMessage e))))
    (try
      (let [m @(:metadata root)
            dnode-atom (:dragging-node m)
            last-drag-atom (:last-drag-time m)]
        (when (and dnode-atom @dnode-atom)
          (let [now (System/currentTimeMillis)
                last @last-drag-atom]
            (when (and last (> (- now last) drag-time-tol-ms))
              (try
                (let [dnode @dnode-atom]
                  (events/emit-widget-event! dnode :drag-stop {:time now})
                  (reset! dnode-atom nil)
                  (reset! last-drag-atom 0))
                (catch Exception _ nil))))))
      (catch Exception e
        (log/error "[FRAME-TICK-META] " (.getMessage e))))))

(defn mouse-click!
  "Match original LambdaLib2 CGUI behavior: event goes to the
  deepest widget only — no ancestor bubbling.  Focus likewise goes to
  the deepest widget, so editable textboxes inside overlays work."
  [root mx my left top button]
  (let [event-key (if (== 0 button) :left-click :right-click)
        hit (traversal/hit-test root mx my left top)]
    (log/info "[MOUSE-CLICK] mx:" mx "my:" my "button:" button "hit-found:" (boolean hit))
    (when hit
      (try
        (log/info "[MOUSE-CLICK] hit pos:" (try (cgui-core/get-pos hit) (catch Exception e (str "ERR:" (.getMessage e)))))
        (when (== 0 button)
          (cgui-screen/gain-focus! root hit))
        (log/info "[MOUSE-CLICK] emitting" event-key "to hit")
        (events/emit-widget-event! hit event-key {:x (- mx left) :y (- my top) :button button})
        (catch Exception e
          (log/error "[MOUSE-CLICK] error in handler:" (.getMessage e))
          (log/stacktrace "[MOUSE-CLICK]" e))))))

(defn mouse-drag!
  "dx/dy are Minecraft's native drag delta — reliable, no position tracking needed."
  [root mx my dx dy left top]
  (try
    (let [rx (- mx left)
          ry (- my top)
          m @(:metadata root)
          dnode-atom (:dragging-node m)
          now (System/currentTimeMillis)]
      (when (and dnode-atom (nil? @dnode-atom))
        (let [hit (traversal/hit-test root rx ry 0 0)]
          (when hit
            (reset! dnode-atom hit)
            (let [ldt-atom (:last-drag-time m)]
              (when ldt-atom (reset! ldt-atom now)))
            (let [st-atom (:last-start-time m)]
              (when st-atom (reset! st-atom now)))
            (events/emit-widget-event! hit :drag-start {:x rx :y ry :dx 0 :dy 0 :time now}))))
      (when-let [hit (or (when dnode-atom @dnode-atom)
                         (traversal/hit-test root rx ry 0 0))]
        (events/emit-widget-event! hit :drag {:x rx :y ry :dx dx :dy dy}))
      nil)
    (catch Exception e
      (log/error "[MOUSE-DRAG] " (.getMessage e)))))

(defn focused-editable-textbox?
  "Returns true when the given tree root has a focused widget with an editable textbox."
  [root]
  (when root
    (let [m @(:metadata root)
          focus-atom (:cgui-focus m)
          focus (when focus-atom @focus-atom)]
      (when focus
        (when-let [tb (components/get-widget-component focus :textbox)]
          (let [st (:state tb)]
            (boolean (and st (:editable? @st)))))))))

(defn focused-widget-owns-key?
  "Returns true when the given tree root has a focused widget that
  handles :key events (either via an editable textbox or explicit
  on-key-press handlers). Used by screen proxies to decide whether to
  consume keyboard input instead of forwarding to vanilla handling."
  [root]
  (when root
    (let [m @(:metadata root)
          focus-atom (:cgui-focus m)
          focus (when focus-atom @focus-atom)]
      (when focus
        (or (when-let [tb (components/get-widget-component focus :textbox)]
              (let [st (:state tb)]
                (boolean (and st (:editable? @st)))))
            (boolean (seq (events/get-widget-event-handlers focus :key))))))))

(defn mouse-scroll!
  [root mx my left top delta-x delta-y]
  ;; Walk the hit-path from deepest to root, find first widget with :mouse-scroll handler
  (let [path (traversal/hit-path root mx my left top)
        target (some #(when (seq (events/get-widget-event-handlers % :mouse-scroll)) %)
                     (reverse path))]
    (when target
      (events/emit-widget-event! target :mouse-scroll
        {:x (- mx left) :y (- my top) :delta-x delta-x :delta-y delta-y}))))

(defn key-input!
  [root key-code scan-code typed-char]
  (when root
    (let [m @(:metadata root)
          focus-atom (:cgui-focus m)
          focus (when focus-atom @focus-atom)
          target (or focus root)]
      (events/emit-widget-event! target :key {:keyCode key-code :scanCode scan-code :typedChar typed-char})
      (when focus
        (when-let [tb (components/get-widget-component focus :textbox)]
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
                    (events/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  (contains? enter-keys key-code)
                  (events/emit-widget-event! focus :confirm-input {:value curr})

                  has-char?
                  (do
                    (swap! st assoc :text (str curr typed-char))
                    (events/emit-widget-event! focus :change-content {:value (str (:text @st))}))

                  :else nil)))))))))
