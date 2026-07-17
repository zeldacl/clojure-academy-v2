(ns cn.li.mcmod.ui.anim
  "Animation/time math (pure functions, zero MC deps).
   Ported from overlay/renderer.clj and optimized.
   All state via cell arrays + partial (Iron Rule 13)."
  (:require [cn.li.mcmod.ui.signal :as sig]))

;; ============================================================================
;; smooth-mask-channel
;; ============================================================================

(defn- ^double smooth-mask-channel [^double from ^double to ^double dt]
  (let [delta (- to from)]
    (if (<= (Math/abs delta) 0.001)
      to
      (+ from (* (Math/signum delta) (min (* 1.0 dt) (Math/abs delta)))))))

;; ============================================================================
;; smoothed — ComputedD wrapper
;; ============================================================================

(defn- ^double smoothed-step [^doubles cell ^double rate ^double target ^double now-ms]
  ;; smooth-toward inlined: a 4-double-arg primitive helper invoked from another
  ;; primitive fn triggers an IFn$DDDDO/invokePrim dispatch mismatch (Clojure
  ;; primitive-return interfaces don't cover 4 double args), so keep the step
  ;; local — all doubles, zero boxing.
  (let [dt (max 0.0 (/ (- now-ms (aget cell 1)) 1000.0))
        current (aget cell 0)
        delta (- target current)
        v (if (<= (Math/abs delta) 0.0005)
            target
            (+ current (* (Math/signum delta) (min (* rate dt) (Math/abs delta)))))]
    (aset cell 0 (double v))
    (aset cell 1 (double now-ms))
    v))

(defn smoothed
  [target-sig clock-ms-sig ^double rate]
  (let [cell (double-array 2)]
    (sig/computed-d [target-sig clock-ms-sig]
                    (partial smoothed-step cell rate))))

;; ============================================================================
;; smoothed-color — ComputedO wrapper (RGBA)
;; ============================================================================

(defn smoothed-color
  [target-sig clock-ms-sig]
  (let [cell (double-array 5)]
    (sig/computed-o [target-sig clock-ms-sig]
                    (fn smooth-color-step [target now-ms]
                      (let [^doubles c cell
                            dt (max 0.0 (/ (- (double now-ms) (aget c 4)) 1000.0))
                            ^double r (smooth-mask-channel (aget c 0) (double (nth target 0 0.0)) dt)
                            ^double g (smooth-mask-channel (aget c 1) (double (nth target 1 0.0)) dt)
                            ^double b (smooth-mask-channel (aget c 2) (double (nth target 2 0.0)) dt)
                            ^double a (smooth-mask-channel (aget c 3) (double (nth target 3 0.0)) dt)]
                        (aset c 0 r)
                        (aset c 1 g)
                        (aset c 2 b)
                        (aset c 3 a)
                        (aset c 4 (double now-ms))
                        [r g b a])))))

;; ============================================================================
;; breathe
;; ============================================================================

(defn- ^double breathe-step [^double period ^double lo ^double hi ^double now-ms]
  (+ lo (* (- hi lo) 0.5 (+ 1.0 (Math/sin (/ (* (double now-ms) 2.0 Math/PI) period))))))

(defn breathe
  [clock-ms-sig ^double period ^double lo ^double hi]
  (sig/computed-d [clock-ms-sig] (partial breathe-step period lo hi)))

;; ============================================================================
;; flicker-alpha
;; ============================================================================

(defn- ^double flicker-alpha-step [^double now-ms]
  (+ 0.725 (* 0.275 (Math/sin (* (double now-ms) 0.003)))))

(defn flicker-alpha
  [clock-ms-sig]
  (sig/computed-d [clock-ms-sig] flicker-alpha-step))

;; ============================================================================
;; jitter-offset
;; ============================================================================

(defn- ^double jitter-offset-step [^double now-ms ^double axis-seed]
  (let [tick (quot (long now-ms) 100)
        seed (unchecked-add-int (unchecked-multiply-int
                                  (unchecked-add-int tick (int axis-seed)) 1103515245) 12345)
        norm (double (bit-and seed 0x7FFFFFFF))
        rnd  (/ norm 2147483647.0)]
    (* 4.0 (- (* 2.0 rnd) 1.0))))

(defn jitter-offset
  [clock-ms-sig ^long axis-seed]
  (sig/computed-d [clock-ms-sig] (partial jitter-offset-step (double axis-seed))))

;; ============================================================================
;; interp-color-stops (baked)
;; ============================================================================

(defn bake-color-stops
  [color-stops]
  (when (seq color-stops)
    (let [sorted (sort-by (fn [s] (double (:pct s 0.0))) color-stops)
          first-s (first sorted)
          last-s  (last sorted)
          first-pct (double (:pct first-s 0.0))
          last-pct  (double (:pct last-s 1.0))
          front-padded (if (> first-pct 0.0)
                         (cons {:pct 0.0 :r (:r first-s 1.0) :g (:g first-s 1.0) :b (:b first-s 1.0)} sorted)
                         sorted)
          padded (cond-> front-padded
                   (< last-pct 1.0)
                   (concat [{:pct 1.0 :r (:r last-s 1.0) :g (:g last-s 1.0) :b (:b last-s 1.0)}]))
          final (sort-by (fn [s] (double (:pct s 0.0))) padded)
          cnt (count final)
          arr (double-array (* 4 cnt))]
      (loop [i 0 remaining final]
        (when-let [s (first remaining)]
          (let [base (* i 4)]
            (aset arr base       (double (:pct s)))
            (aset arr (+ base 1) (double (:r s)))
            (aset arr (+ base 2) (double (:g s)))
            (aset arr (+ base 3) (double (:b s)))
            (recur (unchecked-inc-int i) (rest remaining)))))
      arr)))

(defn sample-color-stops
  "Find the segment [i-1,i] containing p and interpolate; clamp to the last
   stop once i reaches n (covers n=1 and p past the last stop)."
  [^doubles baked ^double pct]
  (when baked
    (let [n (quot (alength baked) 4)
          p (max 0.0 (min 1.0 pct))]
      (loop [i 1]
        (if (>= i n)
          (let [base (* (dec n) 4)]
            (doto (double-array 3)
              (aset 0 (aget baked (+ base 1)))
              (aset 1 (aget baked (+ base 2)))
              (aset 2 (aget baked (+ base 3)))))
          (let [stop-pct (aget baked (* i 4))]
            (if (<= p stop-pct)
              (let [i0 (dec i)
                    base0 (* i0 4)
                    base1 (* i 4)
                    p0 (aget baked base0)
                    p1 (aget baked base1)
                    t  (if (== p1 p0) 0.0 (/ (- p p0) (- p1 p0)))]
                (doto (double-array 3)
                  (aset 0 (+ (aget baked (+ base0 1)) (* t (- (aget baked (+ base1 1)) (aget baked (+ base0 1))))))
                  (aset 1 (+ (aget baked (+ base0 2)) (* t (- (aget baked (+ base1 2)) (aget baked (+ base0 2))))))
                  (aset 2 (+ (aget baked (+ base0 3)) (* t (- (aget baked (+ base1 3)) (aget baked (+ base0 3))))))))
              (recur (unchecked-inc-int i)))))))))
