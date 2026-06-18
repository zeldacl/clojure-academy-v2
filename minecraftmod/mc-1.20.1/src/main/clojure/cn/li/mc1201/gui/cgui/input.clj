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
    (doseq [[widget _ _] (traversal/collect-widgets-z-ordered root [0 0] 1.0 nil)]
      (try
        (events/emit-widget-event! widget :frame event)
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
                (events/emit-widget-event! dnode :drag-stop {:time now})
                (reset! dnode-atom nil)
                (reset! last-drag-atom 0))
              (catch Exception _ nil))))))))

(defn mouse-click!
  "Match original LambdaLib2 CGUI behavior: event goes to the
  deepest widget only — no ancestor bubbling.  Focus likewise goes to
  the deepest widget, so editable textboxes inside overlays work."
  [root mx my left top button]
  (let [event-key (if (== 0 button) :left-click :right-click)
        hit (traversal/hit-test root mx my left top)]
    (when hit
      (when (== 0 button)
        (cgui-screen/gain-focus! root hit))
      (log/info "CGUI mouse-click! mx:" mx "my:" my "left:" left "top:" top "button:" button
                "hit:" (or @(:name hit) "unnamed")
                "has-left-click?" (boolean (seq (get @(:events hit) :left-click)))
                "hit-pos:" (cgui-core/get-pos hit)
                "hit-size:" (cgui-core/get-size hit))
      (events/emit-widget-event!
       hit
       event-key
       {:x (- mx left) :y (- my top) :button button}))))

(defn mouse-drag!
  [root mx my left top]
  (let [rx (- mx left)
        ry (- my top)
        m @(:metadata root)
        prev-x (:last-mouse-x m)
        prev-y (:last-mouse-y m)
        dx (if prev-x (- rx prev-x) 0)
        dy (if prev-y (- ry prev-y) 0)
        dnode-atom (:dragging-node m)
        now (System/currentTimeMillis)]
    ;; Update stored position so next call computes correct delta
    (swap! (:metadata root) assoc :last-mouse-x rx :last-mouse-y ry)
    (when-let [hit (or (when dnode-atom @dnode-atom)
                       (traversal/hit-test root rx ry 0 0))]
      (let [last-drag-atom (:last-drag-time m)
            start-atom (:last-start-time m)]
        (when (and dnode-atom (nil? @dnode-atom))
          (reset! dnode-atom hit)
          (reset! start-atom now)
          (events/emit-widget-event! hit :drag-start {:x rx :y ry :dx dx :dy dy :time now}))
        (when dnode-atom
          (reset! last-drag-atom now))
        (events/emit-widget-event! hit :drag {:x rx :y ry :dx dx :dy dy}))
      ;; Also process as mouse-move to update last position for next delta
      nil)))

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
  (when-let [hit (traversal/hit-test root mx my left top)]
    (events/emit-widget-event! hit :mouse-scroll
      {:x (- mx left) :y (- my top) :delta-x delta-x :delta-y delta-y})))

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
