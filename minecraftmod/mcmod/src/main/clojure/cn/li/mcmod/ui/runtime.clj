(ns cn.li.mcmod.ui.runtime
  "UI Runtime (UiRt) — one instance per screen/overlay.
   All state via one deftype. Field access through public API fns."
  (:require [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.node :as node])
  (:import [cn.li.mcmod.ui.signal SigD SigL IApply]
           [cn.li.mcmod.ui.node INode]
           [java.util ArrayList]))

(deftype UiRt
  [clock-ms partial-ticks game-ticks
   ^ArrayList nodes id->node ^ArrayList dirty-bindings tape
   ^:unsynchronized-mutable tree-dirty
   ^:unsynchronized-mutable ^double screen-w
   ^:unsynchronized-mutable ^double screen-h
   ^:unsynchronized-mutable hovered-idx
   ^:unsynchronized-mutable focus-idx
   ^:unsynchronized-mutable drag-node-idx
   ^:unsynchronized-mutable dragging?
   ^:unsynchronized-mutable ^double drag-start-mx
   ^:unsynchronized-mutable ^double drag-start-my
   ^:unsynchronized-mutable ^long drag-start-ms
   events user-signals
   ^:unsynchronized-mutable disposed?])

(defn create-runtime ^cn.li.mcmod.ui.runtime.UiRt []
  (UiRt. (SigL. 0 (ArrayList. 16))
         (SigD. 0.0 (ArrayList. 16))
         (SigL. 0 (ArrayList. 16))
         (ArrayList. 64) {} (ArrayList. 32) (object-array 0)
         true 0.0 0.0 nil nil nil false 0.0 0.0 0 {} {} false))

;; Clock
(defn clock-ms-sig ^cn.li.mcmod.ui.signal.SigL      [^UiRt rt] (.-clock-ms rt))
(defn partial-ticks-sig ^cn.li.mcmod.ui.signal.SigD  [^UiRt rt] (.-partial-ticks rt))
(defn game-ticks-sig ^cn.li.mcmod.ui.signal.SigL     [^UiRt rt] (.-game-ticks rt))

;; Nodes
(defn register-node! [^UiRt rt id ^INode node]
  (.add ^ArrayList (.-nodes rt) node)
  (when id (set! (.-id->node rt) (assoc (.-id->node rt) id node)))
  node)
(defn node-by-id ^cn.li.mcmod.ui.node.INode [^UiRt rt id] (get (.-id->node rt) id))
(defn node-by-idx ^cn.li.mcmod.ui.node.INode [^UiRt rt ^long idx]
  (let [^ArrayList nodes (.-nodes rt)]
    (when (and (>= idx 0) (< idx (.size nodes))) (.get nodes (int idx)))))

;; Flush
(defn flush! [^UiRt rt]
  (let [^ArrayList q (.-dirty-bindings rt) n (.size q)]
    (when (pos? n)
      (loop [i 0] (when (< i n) (.applyBinding ^IApply (.get q i)) (recur (unchecked-inc-int i))))
      (.clear q))))

;; Resize
(defn resize! [^UiRt rt ^double w ^double h]
  (when (or (not= w (.-screen-w rt)) (not= h (.-screen-h rt)))
    (set! (.-screen-w rt) w) (set! (.-screen-h rt) h) (set! (.-tree-dirty rt) true)))
(defn screen-w ^double [^UiRt rt] (.-screen-w rt))
(defn screen-h ^double [^UiRt rt] (.-screen-h rt))
(defn screen-size [^UiRt rt] [(.-screen-w rt) (.-screen-h rt)])

;; Tree-dirty
(defn mark-tree-dirty! [^UiRt rt] (set! (.-tree-dirty rt) true))
(defn tree-dirty? [^UiRt rt] (boolean (.-tree-dirty rt)))
(defn clear-tree-dirty! [^UiRt rt] (set! (.-tree-dirty rt) false))

;; Tape
(defn get-tape-arr [^UiRt rt] (.-tape rt))
(defn set-tape-arr! [^UiRt rt arr] (set! (.-tape rt) arr))

;; Input
(defn hovered-idx [^UiRt rt] (or (int (.-hovered-idx rt)) -1))
(defn set-hovered-idx! [^UiRt rt idx] (set! (.-hovered-idx rt) (Integer/valueOf (int idx))))
(defn focus-idx [^UiRt rt] (or (int (.-focus-idx rt)) -1))
(defn set-focus-idx! [^UiRt rt idx] (set! (.-focus-idx rt) (Integer/valueOf (int idx))))

;; Events
(defn register-event! [^UiRt rt ^long node-idx event-key handler-fn]
  (let [events (or (.-events rt) {})
        node-events (get events (int node-idx) {})
        handlers (get node-events event-key [])]
    (set! (.-events rt) (assoc events (int node-idx) (assoc node-events event-key (conj handlers handler-fn))))
    nil))
(defn get-event-handlers [^UiRt rt ^long node-idx event-key]
  (get-in (.-events rt) [(int node-idx) event-key]))
(defn remove-node-events! [^UiRt rt ^long node-idx]
  (set! (.-events rt) (dissoc (.-events rt) (int node-idx))) nil)

;; User signals
(defn put-user-signal! [^UiRt rt id s]
  (set! (.-user-signals rt) (assoc (.-user-signals rt) id s)) nil)
(defn user-signal [^UiRt rt id] (get (.-user-signals rt) id))

;; Dispose
(defn dispose! [^UiRt rt]
  (when-not (boolean (.-disposed? rt))
    (set! (.-disposed? rt) true)
    (.clear ^ArrayList (.-nodes rt)) (set! (.-id->node rt) {})
    (.clear ^ArrayList (.-dirty-bindings rt)) (set! (.-tape rt) (object-array 0))
    (set! (.-events rt) {}) (set! (.-user-signals rt) {}) nil))
(defn disposed? [^UiRt rt] (boolean (.-disposed? rt)))

;; Build
(declare build-node!)
(defn- build-children! [rt children parent-idx]
  (when (seq children) (mapv (fn [c] (build-node! rt c parent-idx)) children)))
(defn- build-node! [^UiRt rt spec _parent-idx]
  (let [{:keys [kind id props children]} spec
        kdef (or (get node/kinds kind) (throw (ex-info (str "Unknown node kind: " kind) {:kind kind :spec spec})))
        dslot-cnt (count (:dslots kdef))
        oslot-cnt (:oslots-backend-base kdef (count (:oslots kdef)))
        ^ArrayList nodes (.-nodes rt) idx (.size nodes)
        n (node/create-node idx id kind props dslot-cnt oslot-cnt props)]
    (build-children! rt children idx) (register-node! rt id n) idx))
(defn build! [^UiRt rt spec]
  (let [root-idx (build-node! rt spec -1)] (set! (.-tree-dirty rt) true) root-idx))
