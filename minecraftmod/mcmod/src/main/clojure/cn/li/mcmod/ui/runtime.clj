(ns cn.li.mcmod.ui.runtime
  "UI Runtime (UiRt) — one instance per screen/overlay."
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
  (UiRt. (SigL. 0 (ArrayList. 16)) (SigD. 0.0 (ArrayList. 16)) (SigL. 0 (ArrayList. 16))
         (ArrayList. 64) {} (ArrayList. 32) (object-array 0)
         true 0.0 0.0 nil nil nil false 0.0 0.0 0 {} {} false))

(defn clock-ms-sig ^cn.li.mcmod.ui.signal.SigL      [^UiRt rt] (.clock_ms rt))
(defn partial-ticks-sig ^cn.li.mcmod.ui.signal.SigD  [^UiRt rt] (.partial_ticks rt))
(defn game-ticks-sig ^cn.li.mcmod.ui.signal.SigL     [^UiRt rt] (.game_ticks rt))

(defn register-node! [^UiRt rt id ^INode node]
  (.add ^ArrayList (.nodes rt) node)
  (when id (set! (.id__GT_node rt) (assoc (.id__GT_node rt) id node))) node)
(defn node-by-id ^cn.li.mcmod.ui.node.INode [^UiRt rt id] (get (.id__GT_node rt) id))
(defn node-by-idx ^cn.li.mcmod.ui.node.INode [^UiRt rt ^long idx]
  (let [^ArrayList ns (.nodes rt)]
    (when (and (>= idx 0) (< idx (.size ns))) (.get ns (int idx)))))

(defn flush! [^UiRt rt]
  (let [^ArrayList q (.dirty_bindings rt) n (.size q)]
    (when (pos? n)
      (loop [i 0] (when (< i n) (.applyBinding ^IApply (.get q i)) (recur (unchecked-inc-int i))))
      (.clear q))))

(defn resize! [^UiRt rt ^double w ^double h]
  (when (or (not= w (.screen_w rt)) (not= h (.screen_h rt)))
    (set! (.screen_w rt) w) (set! (.screen_h rt) h) (set! (.tree_dirty rt) true)))
(defn screen-w ^double [^UiRt rt] (.screen_w rt))
(defn screen-h ^double [^UiRt rt] (.screen_h rt))

(defn mark-tree-dirty! [^UiRt rt] (set! (.tree_dirty rt) true))
(defn tree-dirty? [^UiRt rt] (boolean (.tree_dirty rt)))
(defn clear-tree-dirty! [^UiRt rt] (set! (.tree_dirty rt) false))

(defn get-tape-arr [^UiRt rt] (.tape rt))
(defn set-tape-arr! [^UiRt rt arr] (set! (.tape rt) arr))

(defn hovered-idx [^UiRt rt] (or (int (.hovered_idx rt)) -1))
(defn set-hovered-idx! [^UiRt rt idx] (set! (.hovered_idx rt) (Integer/valueOf (int idx))))
(defn focus-idx [^UiRt rt] (or (int (.focus_idx rt)) -1))
(defn set-focus-idx! [^UiRt rt idx] (set! (.focus_idx rt) (Integer/valueOf (int idx))))

(defn register-event! [^UiRt rt ^long node-idx event-key handler-fn]
  (let [events (.events rt) node-events (get events (int node-idx) {})
        handlers (get node-events event-key [])]
    (set! (.events rt) (assoc events (int node-idx) (assoc node-events event-key (conj handlers handler-fn)))) nil))
(defn get-event-handlers [^UiRt rt ^long node-idx event-key]
  (get-in (.events rt) [(int node-idx) event-key]))
(defn remove-node-events! [^UiRt rt ^long node-idx]
  (set! (.events rt) (dissoc (.events rt) (int node-idx))) nil)

(defn put-user-signal! [^UiRt rt id s] (set! (.user_signals rt) (assoc (.user_signals rt) id s)) nil)
(defn user-signal [^UiRt rt id] (get (.user_signals rt) id))

(defn dispose! [^UiRt rt]
  (when-not (boolean (.disposed_QMARK_ rt)) (set! (.disposed_QMARK_ rt) true)
    (.clear ^ArrayList (.nodes rt)) (set! (.id__GT_node rt) {})
    (.clear ^ArrayList (.dirty_bindings rt)) (set! (.tape rt) (object-array 0))
    (set! (.events rt) {}) (set! (.user_signals rt) {}) nil))
(defn disposed? [^UiRt rt] (boolean (.disposed_QMARK_ rt)))

(declare build-node!)
(defn- build-children! [rt children parent-idx]
  (when (seq children) (mapv (fn [c] (build-node! rt c parent-idx)) children)))
(defn- build-node! [^UiRt rt spec _parent-idx]
  (let [{:keys [kind id props children]} spec
        kdef (or (get node/kinds kind) (throw (ex-info (str "Unknown kind: " kind) {:kind kind})))
        dslot-cnt (count (:dslots kdef)) oslot-cnt (:oslots-backend-base kdef (count (:oslots kdef)))
        ^ArrayList ns (.nodes rt) idx (.size ns)
        n (node/create-node idx id kind props dslot-cnt oslot-cnt props)]
    (build-children! rt children idx) (register-node! rt id n) idx))
(defn build! [^UiRt rt spec]
  (let [root-idx (build-node! rt spec -1)] (set! (.tree_dirty rt) true) root-idx))
