(ns cn.li.mcmod.ui.events
  "Event dispatch & focus/drag management (pure logic)."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.layout :as layout]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.slot-write :as slot-write])
  (:import [cn.li.mcmod.uipojo.runtime UiRt]
           [cn.li.mcmod.ui.node INode]))

(defn on! [^UiRt rt id event-key f]
  (if-let [^INode node (rt/node-by-id rt id)]
    (rt/register-event! rt (.getIdx node) event-key f)
    (throw (ex-info (str "Node not found: " id) {:id id :event-key event-key})))
  nil)

(defn on-confirm-input
  "Register :confirm-input handler. Event map includes :value (current text)."
  [^UiRt rt id f]
  (on! rt id :confirm-input
       (fn [rt node evt] (f rt node (:value evt)))))

(defn on-change-content
  "Register :change-content handler. Event map includes :value (current text)."
  [^UiRt rt id f]
  (on! rt id :change-content
       (fn [rt node evt] (f rt node (:value evt)))))

(defn- text-editable? [^INode n]
  (boolean (get (.getStaticProps n) :editable?)))

(defn- text-value [^INode n]
  (str (or (.getOSlot n 0) "")))

(defn- set-text-value! [^INode n value]
  (slot-write/apply-prop! n :text :text (str value)))

(defn- emit-text-event! [^UiRt rt ^INode n event-key]
  (when-let [handlers (rt/get-event-handlers rt (.getIdx n) event-key)]
    (let [evt {:value (text-value n) :node-idx (.getIdx n)}]
      (doseq [f handlers] (f rt n evt)))))

(defn dispatch-editable-key!
  "Handle backspace / enter / char for focused editable :text nodes.
   Returns true when the key was consumed."
  [^UiRt rt key-code typed-char]
  (let [focus-idx (rt/focus-idx rt)]
    (when (>= focus-idx 0)
      (when-let [^INode n (rt/node-by-idx rt focus-idx)]
        (when (text-editable? n)
          (let [enter-keys #{257 335 28}
                backspace-keys #{259 14}
                has-char? (and typed-char (not= typed-char (char 0)))]
            (cond
              (contains? backspace-keys key-code)
              (let [curr (text-value n)]
                (when (pos? (count curr))
                  (set-text-value! n (subs curr 0 (dec (count curr))))
                  (emit-text-event! rt n :change-content))
                true)

              (contains? enter-keys key-code)
              (do (emit-text-event! rt n :confirm-input) true)

              has-char?
              (do (set-text-value! n (str (text-value n) typed-char))
                  (emit-text-event! rt n :change-content)
                  true)

              :else false)))))))

(defn dispatch-click! [^UiRt rt mx my button event-key]
  ;; Bubble up the parent chain from the deepest hit (same walk as
  ;; dispatch-scroll!): handlers live on container groups (slot rows, carousel
  ;; pages) while hit-test lands on their leaf children (icons, labels,
  ;; background images) — without bubbling those handlers are unreachable.
  ;; The walk stops at the FIRST node with handlers, so a child with its own
  ;; handler still shadows its ancestors.
  (let [^INode hit (layout/hit-test rt (double mx) (double my))]
    (loop [node hit]
      (when node
        (if-let [handlers (rt/get-event-handlers rt (.getIdx ^INode node) event-key)]
          (let [evt {:x mx :y my :button button :node-idx (.getIdx ^INode node)}]
            (doseq [f handlers] (f rt node evt)))
          (recur (.getParentNode ^INode node)))))))

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
          (let [evt {:key-code key-code :scan-code scan-code :modifiers modifiers
                     :action action :node-idx focus-idx}]
            (doseq [f handlers] (f rt node evt))))))))

(defn dispatch-char! [^UiRt rt code-point]
  (let [focus-idx (rt/focus-idx rt)]
    (when (>= focus-idx 0)
      (when-let [handlers (rt/get-event-handlers rt focus-idx :change-content)]
        (when-let [^INode node (rt/node-by-idx rt focus-idx)]
          (let [evt {:char (.toString (Character/toChars (int code-point)))
                     :code-point (int code-point) :node-idx focus-idx}]
            (doseq [f handlers] (f rt node evt))))))))

(defn gain-focus! [^UiRt rt node-idx]
  (let [old-idx (rt/focus-idx rt)]
    (when (and (>= old-idx 0) (not= old-idx node-idx))
      (when-let [^INode old-node (rt/node-by-idx rt old-idx)]
        (.clearFlag old-node node/FLAG-FOCUSED)
        (.setFlag old-node node/FLAG-RENDER-DIRTY))
      (when-let [handlers (rt/get-event-handlers rt old-idx :lost-focus)]
        (when-let [^INode old-node (rt/node-by-idx rt old-idx)]
          (let [evt {:node-idx old-idx :new-focus-idx node-idx}]
            (doseq [f handlers] (f rt old-node evt))))))
    (rt/set-focus-idx! rt node-idx)
    (when (>= node-idx 0)
      (when-let [^INode node (rt/node-by-idx rt node-idx)]
        (.setFlag node node/FLAG-FOCUSED)
        (.setFlag node node/FLAG-RENDER-DIRTY))
      (when-let [handlers (rt/get-event-handlers rt node-idx :gain-focus)]
        (when-let [^INode node (rt/node-by-idx rt node-idx)]
          (let [evt {:node-idx node-idx}]
            (doseq [f handlers] (f rt node evt))))))))

(defn remove-focus! [^UiRt rt]
  (let [old-idx (rt/focus-idx rt)]
    (when (>= old-idx 0) (gain-focus! rt -1))))

(defn dispatch-mouse-press! [^UiRt rt mx my button]
  (let [^INode hit (layout/hit-test rt (double mx) (double my))
        hit-idx (if hit (.getIdx hit) -1)]
    (rt/set-drag-node-idx! rt hit-idx)
    (rt/set-drag-start-mx! rt (double mx))
    (rt/set-drag-start-my! rt (double my))
    (rt/set-drag-start-ms! rt (System/currentTimeMillis))
    (rt/set-dragging?! rt false)
    (gain-focus! rt hit-idx)
    (dispatch-click! rt mx my button :left-click)))

(defn dispatch-mouse-release! [^UiRt rt mx my button]
  (let [was-dragging? (rt/dragging? rt)]
    (when was-dragging?
      (when-let [handlers (rt/get-event-handlers rt (rt/drag-node-idx rt) :drag-stop)]
        (when-let [^INode node (rt/node-by-idx rt (rt/drag-node-idx rt))]
          (let [evt {:x mx :y my :button button :node-idx (rt/drag-node-idx rt)
                     :start-mx (rt/drag-start-mx rt) :start-my (rt/drag-start-my rt)}]
            (doseq [f handlers] (f rt node evt))))))
    (rt/set-dragging?! rt false)
    (rt/set-drag-node-idx! rt -1)))

(defn dispatch-mouse-drag! [^UiRt rt mx my button]
  (let [node-idx (rt/drag-node-idx rt)]
    (when (>= node-idx 0)
      (if (rt/dragging? rt)
        (when-let [handlers (rt/get-event-handlers rt node-idx :drag)]
          (when-let [^INode node (rt/node-by-idx rt node-idx)]
            (let [evt {:x mx :y my :button button :node-idx node-idx
                       :dx (- (double mx) (rt/drag-start-mx rt))
                       :dy (- (double my) (rt/drag-start-my rt))
                       :start-mx (rt/drag-start-mx rt) :start-my (rt/drag-start-my rt)}]
              (doseq [f handlers] (f rt node evt)))))
        (let [dx (Math/abs (- (double mx) (rt/drag-start-mx rt)))
              dy (Math/abs (- (double my) (rt/drag-start-my rt)))]
          (when (or (> dx 4.0) (> dy 4.0))
            (rt/set-dragging?! rt true)
            (when-let [handlers (rt/get-event-handlers rt node-idx :drag-start)]
              (when-let [^INode node (rt/node-by-idx rt node-idx)]
                (let [evt {:x mx :y my :button button :node-idx node-idx
                           :start-mx (rt/drag-start-mx rt) :start-my (rt/drag-start-my rt)}]
                  (doseq [f handlers] (f rt node evt)))))))))))

(defn unbind-subtree! [^UiRt rt ^INode node]
  (rt/unbind-subtree! rt node))
