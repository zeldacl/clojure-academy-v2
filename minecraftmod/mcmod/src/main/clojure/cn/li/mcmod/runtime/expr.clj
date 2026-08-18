(ns cn.li.mcmod.runtime.expr
  "Pure primitive-friendly expression helpers used by combat and VFX." )

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn ^double add [^double a ^double b] (+ a b))
(defn ^double sub [^double a ^double b] (- a b))
(defn ^double mul [^double a ^double b] (* a b))
(defn ^double div [^double a ^double b]
  (when (zero? b)
    (throw (ex-info "division by zero" {:a a :b b})))
  (/ a b))
(defn ^double clamp [^double value ^double lo ^double hi]
  (max lo (min hi value)))
(defn ^double lerp [^double lo ^double hi ^double t]
  (let [^double bounded (clamp t (double 0.0) (double 1.0))]
    (+ lo (* (- hi lo) bounded))))

(defn ^double vec3-dot
  [ax ay az bx by bz]
  (+ (* (double ax) (double bx))
     (* (double ay) (double by))
     (* (double az) (double bz))))

(defn ^double vec3-distance
  [ax ay az bx by bz]
  (let [ax (double ax) ay (double ay) az (double az)
        bx (double bx) by (double by) bz (double bz)]
  (Math/sqrt (+ (Math/pow (- ax bx) 2.0)
                (Math/pow (- ay by) 2.0)
                (Math/pow (- az bz) 2.0)))))

(defn ^double normalize-component [^double value ^double length]
  (if (zero? length) 0.0 (/ value length)))

(defn vec3-add! [^doubles out ^doubles a ^doubles b]
  (aset-double out 0 (+ (aget a 0) (aget b 0)))
  (aset-double out 1 (+ (aget a 1) (aget b 1)))
  (aset-double out 2 (+ (aget a 2) (aget b 2)))
  out)

(defn vec3-sub! [^doubles out ^doubles a ^doubles b]
  (aset-double out 0 (- (aget a 0) (aget b 0)))
  (aset-double out 1 (- (aget a 1) (aget b 1)))
  (aset-double out 2 (- (aget a 2) (aget b 2)))
  out)

(defn vec3-scale! [^doubles out ^doubles a ^double scale]
  (aset-double out 0 (* (aget a 0) scale))
  (aset-double out 1 (* (aget a 1) scale))
  (aset-double out 2 (* (aget a 2) scale))
  out)

(defn vec3-cross! [^doubles out ^doubles a ^doubles b]
  (let [ax (aget a 0) ay (aget a 1) az (aget a 2)
        bx (aget b 0) by (aget b 1) bz (aget b 2)]
    (aset-double out 0 (- (* ay bz) (* az by)))
    (aset-double out 1 (- (* az bx) (* ax bz)))
    (aset-double out 2 (- (* ax by) (* ay bx)))
    out))

(defn ^double vec3-length [^doubles value]
  (Math/sqrt (+ (* (aget value 0) (aget value 0))
                (* (aget value 1) (aget value 1))
                (* (aget value 2) (aget value 2)))))

(defn vec3-normalize! [^doubles out ^doubles value]
  (let [length-value (Math/sqrt (+ (* (aget value 0) (aget value 0))
                                   (* (aget value 1) (aget value 1))
                                   (* (aget value 2) (aget value 2))))]
    (if (zero? length-value)
      (do (aset-double out 0 0.0) (aset-double out 1 0.0) (aset-double out 2 0.0))
      (vec3-scale! out value (/ 1.0 length-value)))
    out))
