(ns cn.li.mcmod.ui.events
  "Event dispatch & focus/drag management (pure logic)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.node :as node])
  (:import [cn.li.mcmod.ui.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(defn on! [^UiRt rt id event-key f]
  (if-let [^INode node (rt/node-by-id rt id)]
    (rt/register-event! rt (.getIdx node) event-key f)
    (throw (ex-info (str "Node not found: " id) {:id id :event-key event-key})))
  nil)

(defn dispatch-click! [^UiRt rt mx my button event-key]
  (let [^INode hit (layout/hit-test rt (double mx) (double my))]
    (when hit
      (when-let [handlers (rt/get-event-handlers rt (.getIdx hit) event-key)]
        (let [evt {:x mx :y my :button button :node-idx (.getIdx hit)}]
          (doseq [f handlers] (f rt hit evt)))))))

(defn dispatch-scroll! [^UiRt rt mx my scroll-delta]
  (let [^INode hit (layout/hit-test rt (double mx) (double my))]
    (loop [node hit]
      (when node
        (if-let [handlers (rt/get-event-handlers rt (.getIdx ^INode node) :mouse-scroll)]
          (let [evt {:x mx :y my :delta scroll-delta :node-idx (.getIdx ^INode node)}]
            (doseq [f handlers] (f rt node evt)))
          (recur (.getParentNode ^INode node)))))))

(defn dispatch-key! [^UiRt rt key-code scan-code modifiers action]
  (let [focus-idx (rt/focus-idx rt)]
    (when (>= focus-idx 0)
      (when-let [handlers (rt/get-event-handlers rt focus-idx :key)]
        (when-let [^INode node (rt/node-by-idx rt focus-idx)]
          (let [evt {:key-code key-code :scan-code scan-code :modifiers modifiers :action action :node-idx focus-idx}]
            (doseq [f handlers] (f rt node evt))))))))

(defn dispatch-char! [^UiRt rt code-point]
  (let [focus-idx (rt/focus-idx rt)]
    (when (>= focus-idx 0)
      (when-let [handlers (rt/get-event-handlers rt focus-idx :change-content)]
        (when-let [^INode node (rt/node-by-idx rt focus-idx)]
          (let [evt {:char (.toString (Character/toChars (int code-point))) :code-point (int code-point) :node-idx focus-idx}]
            (doseq [f handlers] (f rt node evt))))))))

(defn gain-focus! [^UiRt rt node-idx]
  (let [old-idx (rt/focus-idx rt)]
    (when (and (>= old-idx 0) (not= old-idx node-idx))
      (when-let [handlers (rt/get-event-handlers rt old-idx :lost-focus)]
        (when-let [^INode old-node (rt/node-by-idx rt old-idx)]
          (let [evt {:node-idx old-idx :new-focus-idx node-idx}]
            (doseq [f handlers] (f rt old-node evt))))))
    (rt/set-focus-idx! rt node-idx)
    (when (>= node-idx 0)
      (when-let [handlers (rt/get-event-handlers rt node-idx :gain-focus)]
        (when-let [^INode node (rt/node-by-idx rt node-idx)]
          (let [evt {:node-idx node-idx}] (doseq [f handlers] (f rt node evt))))))))

(defn remove-focus! [^UiRt rt]
  (let [old-idx (rt/focus-idx rt)] (when (>= old-idx 0) (gain-focus! rt -1))))

;; Drag state accessors (direct field access on UiRt)
(defn- drag-node-idx [^UiRt rt] (.drag_node_idx rt))
(defn- set-drag-node-idx! [^UiRt rt v] (set! (.drag_node_idx rt) v))
(defn- dragging? [^UiRt rt] (boolean (.dragging_QMARK_ rt)))
(defn- set-dragging?! [^UiRt rt v] (set! (.dragging_QMARK_ rt) (boolean v)))
(defn- drag-start-mx [^UiRt rt] (.drag_start_mx rt))
(defn- set-drag-start-mx! [^UiRt rt v] (set! (.drag_start_mx rt) (double v)))
(defn- drag-start-my [^UiRt rt] (.drag_start_my rt))
(defn- set-drag-start-my! [^UiRt rt v] (set! (.drag_start_my rt) (double v)))
(defn- drag-start-ms [^UiRt rt] (.drag_start_ms rt))
(defn- set-drag-start-ms! [^UiRt rt v] (set! (.drag_start_ms rt) (long v)))

(defn dispatch-mouse-press! [^UiRt rt mx my button]
  (let [^INode hit (layout/hit-test rt (double mx) (double my))
        hit-idx (if hit (.getIdx hit) -1)]
    (set-drag-node-idx! rt hit-idx) (set-drag-start-mx! rt (double mx))
    (set-drag-start-my! rt (double my)) (set-drag-start-ms! rt (System/currentTimeMillis))
    (set-dragging?! rt false) (gain-focus! rt hit-idx)
    (dispatch-click! rt mx my button :left-click)))

(defn dispatch-mouse-release! [^UiRt rt mx my button]
  (let [was-dragging? (dragging? rt)]
    (when was-dragging?
      (when-let [handlers (rt/get-event-handlers rt (drag-node-idx rt) :drag-stop)]
        (when-let [^INode node (rt/node-by-idx rt (drag-node-idx rt))]
          (let [evt {:x mx :y my :button button :node-idx (drag-node-idx rt)
                     :start-mx (drag-start-mx rt) :start-my (drag-start-my rt)}]
            (doseq [f handlers] (f rt node evt))))))
    (set-dragging?! rt false) (set-drag-node-idx! rt -1)))

(defn dispatch-mouse-drag! [^UiRt rt mx my button]
  (let [node-idx (drag-node-idx rt)]
    (when (>= node-idx 0)
      (if (dragging? rt)
        (when-let [handlers (rt/get-event-handlers rt node-idx :drag)]
          (when-let [^INode node (rt/node-by-idx rt node-idx)]
            (let [evt {:x mx :y my :button button :node-idx node-idx
                       :dx (- (double mx) (drag-start-mx rt)) :dy (- (double my) (drag-start-my rt))
                       :start-mx (drag-start-mx rt) :start-my (drag-start-my rt)}]
              (doseq [f handlers] (f rt node evt)))))
        (let [dx (Math/abs (- (double mx) (drag-start-mx rt)))
              dy (Math/abs (- (double my) (drag-start-my rt)))]
          (when (or (> dx 4.0) (> dy 4.0))
            (set-dragging?! rt true)
            (when-let [handlers (rt/get-event-handlers rt node-idx :drag-start)]
              (when-let [^INode node (rt/node-by-idx rt node-idx)]
                (let [evt {:x mx :y my :button button :node-idx node-idx
                           :start-mx (drag-start-mx rt) :start-my (drag-start-my rt)}]
                  (doseq [f handlers] (f rt node evt)))))))))))

(defn unbind-subtree! [^UiRt rt ^INode node]
  (rt/unbind-subtree! rt node))
