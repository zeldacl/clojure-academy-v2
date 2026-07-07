(ns cn.li.mcmod.ui.signal
  "Fine-grained explicit-dependency signal core.

   SolidJS-style reactive: static node tree + signals bound to node props.
   Value changes dirty only affected nodes; animations go through clock signals.

   Design constraints:
   - All project-own definterface + deftype (Iron Rule 5/7, remap-safe)
   - Explicit dependencies (not auto-tracking)
   - Primitive interface methods (no boxing end-to-end)
   - Hot paths: zero lazy seqs, zero reallocations (Iron Rule 4/13)
   - No keyword-as-fn in HOF positions (Iron Rule 1)"
  (:import [java.util ArrayList]))

;; ============================================================================
;; definterface
;; ============================================================================

(definterface ISigO
  (^Object sGet [])
  (^void sSet [^Object v]))

(definterface ISigD
  (^double dGet [])
  (^void dSet [^double v]))

(definterface ISigL
  (^long lGet [])
  (^void lSet [^long v]))

(definterface IDep
  (^void depMarkDirty []))

(definterface IApply
  (^void applyBinding []))

;; ============================================================================
;; Source signals (mutable, writable)
;; ============================================================================

(deftype SigD [^:unsynchronized-mutable ^double value
               ^ArrayList outs]
  ISigD
  (dGet [_] value)
  (dSet [_ v]
    (when-not (== value v)
      (set! value v)
      (let [n (.size outs)]
        (loop [i 0]
          (when (< i n)
            (.depMarkDirty ^IDep (.get outs i))
            (recur (unchecked-inc-int i))))))))

(deftype SigL [^:unsynchronized-mutable ^long value
               ^ArrayList outs]
  ISigL
  (lGet [_] value)
  (lSet [_ v]
    (when-not (== value v)
      (set! value v)
      (let [n (.size outs)]
        (loop [i 0]
          (when (< i n)
            (.depMarkDirty ^IDep (.get outs i))
            (recur (unchecked-inc-int i))))))))

(deftype SigO [^:unsynchronized-mutable ^Object value
               ^ArrayList outs]
  ISigO
  (sGet [_] value)
  (sSet [_ v]
    (when-not (= value v)
      (set! value v)
      (let [n (.size outs)]
        (loop [i 0]
          (when (< i n)
            (.depMarkDirty ^IDep (.get outs i))
            (recur (unchecked-inc-int i))))))))

;; ============================================================================
;; Source reader helper
;; ============================================================================

(defn- read-source-as-double [s]
  (if (instance? cn.li.mcmod.ui.signal.ISigD s)
    (.dGet ^ISigD s)
    (if (instance? cn.li.mcmod.ui.signal.ISigL s)
      (double (.lGet ^ISigL s))
      (double (.sGet ^ISigO s)))))

(deftype ComputedD [ s0                     ;; source 0 (always present)
                    s1                     ;; source 1 (nil if n<2)
                    s2                     ;; source 2 (nil if n<3)
                    more-sources           ;; seq of remaining sources (nil if n<=3)
                    f
                    ^long n-sources
                    ^:unsynchronized-mutable ^double value
                    ^:unsynchronized-mutable dirty
                    ^ArrayList outs]
  IDep
  (depMarkDirty [_]
    (when-not dirty
      (set! dirty true)
      (let [n (.size outs)]
        (loop [i 0]
          (when (< i n)
            (.depMarkDirty ^IDep (.get outs i))
            (recur (unchecked-inc-int i)))))))
  ISigD
  (dGet [_]
    (when dirty
      (set! dirty false)
      (set! value (double
                    (case n-sources
                      0 (f)
                      1 (f (read-source-as-double s0))
                      2 (f (read-source-as-double s0) (read-source-as-double s1))
                      3 (f (read-source-as-double s0) (read-source-as-double s1)
                           (read-source-as-double s2))
                      (apply f (map read-source-as-double (cons s0 (cons s1 (cons s2 more-sources)))))))))
    value)
  (dSet [_ _v]
    (throw (UnsupportedOperationException. "ComputedD is read-only"))))

(deftype ComputedO [ s0
                    s1
                    s2
                    more-sources
                    f
                    ^long n-sources
                    ^:unsynchronized-mutable ^Object value
                    ^:unsynchronized-mutable dirty
                    ^ArrayList outs]
  IDep
  (depMarkDirty [_]
    (when-not dirty
      (set! dirty true)
      (let [n (.size outs)]
        (loop [i 0]
          (when (< i n)
            (.depMarkDirty ^IDep (.get outs i))
            (recur (unchecked-inc-int i)))))))
  ISigO
  (sGet [_]
    (when dirty
      (set! dirty false)
      (set! value
        (case n-sources
          0 (f)
          1 (f (.sGet ^ISigO s0))
          2 (f (.sGet ^ISigO s0) (.sGet ^ISigO s1))
          3 (f (.sGet ^ISigO s0) (.sGet ^ISigO s1) (.sGet ^ISigO s2))
          (let [vs (map #(.sGet ^ISigO %) (cons s0 (cons s1 (cons s2 more-sources))))]
            (apply f vs)))))
    value)
  (sSet [_ _v]
    (throw (UnsupportedOperationException. "ComputedO is read-only"))))

;; ============================================================================
;; Binding (signal -> node property)
;; ============================================================================

(deftype Binding [source
                  node
                  apply-fn
                  ^:unsynchronized-mutable queued
                  ^ArrayList flush-queue]
  IDep
  (depMarkDirty [_this]
    (when-not queued
      (set! queued true)
      (.add flush-queue _this)))
  IApply
  (applyBinding [_this]
    (set! queued false)
    (apply-fn node source)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn signal-d ^cn.li.mcmod.ui.signal.SigD [^double init]
  (SigD. init (ArrayList. 4)))

(defn signal-l ^cn.li.mcmod.ui.signal.SigL [^long init]
  (SigL. init (ArrayList. 4)))

(defn signal-o ^cn.li.mcmod.ui.signal.SigO [init]
  (SigO. init (ArrayList. 4)))

(defn sget-d ^double [^ISigD s]
  (.dGet s))

(defn sget-l ^long [^ISigL s]
  (.lGet s))

(defn sget-o [^ISigO s]
  (.sGet s))

(defn sset-d! [^ISigD s ^double v]
  (.dSet s v))

(defn sset-l! [^ISigL s ^long v]
  (.lSet s v))

(defn sset-o! [^ISigO s v]
  (.sSet s v))

;; Internal helpers

(defn ^:no-doc add-dep! [^ArrayList outs dep]
  (.add outs dep))

(defn ^:no-doc remove-dep! [^ArrayList outs dep]
  (.remove outs dep))

(defn- outs-of [s]
  "Get the outs ArrayList from a signal. Type-hinted for reflection-free access."
  (if (instance? cn.li.mcmod.ui.signal.SigD s)
    (.-outs ^cn.li.mcmod.ui.signal.SigD s)
    (if (instance? cn.li.mcmod.ui.signal.SigL s)
      (.-outs ^cn.li.mcmod.ui.signal.SigL s)
      (.-outs ^cn.li.mcmod.ui.signal.SigO s))))

(defn computed-d ^cn.li.mcmod.ui.signal.ComputedD [sources f]
  (let [srcs (vec sources)
        n   (count srcs)
        s0  (nth srcs 0 nil)
        s1  (nth srcs 1 nil)
        s2  (nth srcs 2 nil)
        more (when (> n 3) (subvec srcs 3))
        c   (ComputedD. s0 s1 s2 more f (long n) 0.0 (Boolean/valueOf true) (ArrayList. 4))]
    (doseq [s srcs]
      (add-dep! (outs-of s) c))
    c))

(defn computed-o ^cn.li.mcmod.ui.signal.ComputedO [sources f]
  (let [srcs (vec sources)
        n   (count srcs)
        s0  (nth srcs 0 nil)
        s1  (nth srcs 1 nil)
        s2  (nth srcs 2 nil)
        more (when (> n 3) (subvec srcs 3))
        c   (ComputedO. s0 s1 s2 more f (long n) nil (Boolean/valueOf true) (ArrayList. 4))]
    (doseq [s srcs]
      (add-dep! (outs-of s) c))
    c))

(defn bind! ^cn.li.mcmod.ui.signal.Binding [source node apply-fn ^ArrayList flush-queue]
  (let [b (Binding. source node apply-fn false flush-queue)]
    (add-dep! (outs-of source) b)
    b))

(defn unbind! [^Binding b]
  (remove-dep! (outs-of (.-source b)) b)
  nil)
