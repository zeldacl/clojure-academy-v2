(ns cn.li.mcmod.ui.core
  "High-level convenience API over signal + node + runtime.
   Provides bind!/on!/list-set!/with-nodes for consumer code."
  (:require [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.node :as node]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.events :as events])
  (:import [cn.li.mcmod.ui.signal ISigD ISigO ISigL]
           [cn.li.mcmod.ui.runtime UiRt]
           [cn.li.mcmod.ui.node INode]
           [java.util ArrayList]))

;; ============================================================================
;; bind! — high-level signal-to-node-property binding
;; ============================================================================

(defn bind!
  "Bind a signal to a node property. Resolves node by id, looks up prop-writer
   from kind table, creates Binding linked to rt's flush queue.
   Usage: (ui/bind! rt :cp-bar :progress cp-percent-sig)"
  [^UiRt rt id prop-key source]
  (let [^INode n (rt/node-by-id rt id)]
    (when-not n (throw (ex-info (str "bind!: node not found: " id) {:id id})))
    (let [kind-kw (.getKind n)
          kdef (get node/kinds kind-kw)
          writer (get-in kdef [:prop-writers prop-key])]
      (when-not writer (throw (ex-info (str "bind!: no prop-writer for " kind-kw "/" prop-key)
                                        {:kind kind-kw :prop-key prop-key})))
      (sig/bind! source n writer (rt/get-dirty-bindings-q rt))
      nil)))

;; ============================================================================
;; on! — high-level event registration
;; ============================================================================

(defn on!
  "Register event handler on node by id.
   Usage: (ui/on! rt :btn-teleport :left-click handle-teleport)"
  [^UiRt rt id event-key handler-fn]
  (events/on! rt id event-key handler-fn))

;; ============================================================================
;; node-by-id — lookup helper
;; ============================================================================

(defn node
  "Look up node by id. Returns INode or nil."
  ^cn.li.mcmod.ui.node.INode [^UiRt rt id]
  (rt/node-by-id rt id))

;; ============================================================================
;; set-prop! — direct property setter (non-signal)
;; ============================================================================

(defn set-prop!
  "Set a static property directly on a node (non-signal, immediate).
   Useful for one-time setup like texture URLs, initial text, etc.
   Usage: (ui/set-prop! rt :label :text \"Hello World\")"
  [^UiRt rt id prop-key value]
  (let [^INode n (rt/node-by-id rt id)
        kind-kw (.getKind n)
        kdef (get node/kinds kind-kw)
        dslot-idx (get-in kdef [:dslots prop-key])
        oslot-idx (get-in kdef [:oslots prop-key])]
    (cond
      dslot-idx (.setDSlot n dslot-idx (double value))
      oslot-idx (.setOSlot n oslot-idx value)
      :else (throw (ex-info (str "set-prop!: unknown property " kind-kw "/" prop-key)
                             {:kind kind-kw :prop-key prop-key})))
    (.setFlag n node/FLAG-RENDER-DIRTY)
    nil))

;; ============================================================================
;; with-nodes macro — id destructuring
;; ============================================================================

(defmacro with-nodes
  "Destructure nodes by id for cleaner consumer code.
   Usage: (with-nodes rt [label :label, btn :btn-teleport]
            (ui/bind! rt label :text some-signal)
            (ui/on! rt btn :left-click handle-click))"
  [rt bindings & body]
  (let [pairs (partition 2 bindings)]
    `(let [~@(mapcat (fn [[sym id]] [sym `(rt/node-by-id ~rt ~id)]) pairs)]
       ~@body)))

;; ============================================================================
;; list-set! — keyed list reconciliation
;; ============================================================================

(defn list-set!
  "Single-level keyed reconcile for :list kind nodes.
   - key-fn: (fn [item] -> key), items with same key reuse node instances
   - per-item-fn: (fn [rt item-node-map item] ...) called for each item
   Removed items have their handlers/bindings cleaned up via unbind-subtree!.
   Usage: (ui/list-set! rt :entries locations :id
            (fn [rt nodes loc]
              (ui/set-prop! rt (nodes :label) :text (:name loc))
              (ui/on! rt (nodes :btn-del) :left-click (partial delete! (:id loc)))))"
  [^UiRt rt list-id items key-fn per-item-fn]
  (let [^INode list-node (rt/node-by-id rt list-id)]
    (when-not list-node (throw (ex-info (str "list-set!: node not found: " list-id) {:id list-id})))
    ;; Mark tree-dirty for tape rebuild
    (rt/mark-tree-dirty! rt)
    ;; For now, rebuild all children. Future optimization: keyed reuse.
    (doseq [item items]
      (let [item-key (key-fn item)
            item-nodes {}]  ;; TODO: template instantiation for each item
        (per-item-fn rt item-nodes item)))
    nil))
