(ns cn.li.vfx.compile
  "Emitter module stack -> compiled closures over cn.li.vfx.layout's column
   offsets, closing directly over primitive array indices instead of doing
   an attribute->column lookup per particle per tick.

   SCOPE CUT, stated plainly, twice:

   1. Module declarations are plain EDN maps ({:module :spawn/burst :count
      48}), not S-expression calls needing their own expression sub-
      language: a field's value is either a literal (used as-is) or
      [:context k] (resolved once, at COMPILE time, from a caller-supplied
      context map of already-resolved :user capability values -- see
      compile-emitter). There is no per-particle expression evaluation and
      no RNG in this pass -- :spawn/burst's particles all start identical
      apart from a spread a future module would add.

   2. Only :float-bank attributes are supported (:vec3/:float -- position,
      velocity, age, lifetime, size, alpha and similar). :color/:long
      (the layout's INT bank) needs a second array + a distinct writer
      shape per module; deferred rather than guessed at, since nothing in
      this pass's representative effect (spawn -> integrate -> curve ->
      expire) needs it.

   This proves the Niagara module-stack MODEL end-to-end (attribute
   declaration -> layout -> compiled closures -> real simulation) with a
   representative module set (spawn/burst, location/line, velocity/const,
   set, forces/apply, integrate, curve, kill-expired) -- not the full
   authoring vocabulary the redesign plan sketches. Richer per-module
   expressions and int-bank attributes are natural, additive follow-ups
   once this model is proven, not a redesign."
  (:require [cn.li.vfx.layout :as layout])
  (:import [cn.li.mcmod.runtime.vfx ParticleColumns]))

(set! *warn-on-reflection* true)

(defn- resolve-field [v context]
  (if (and (vector? v) (= 2 (count v)) (= :context (first v)))
    (get context (second v))
    v))

(defn- vec3-of [v] (mapv double v))

(defn- float-col
  "The single column index of a scalar :float attribute -- throws if
   `attr` was never declared in this layout (see :attrs on
   compile-emitter) or is a multi-column (:vec3) attribute."
  ^long [layout attr]
  (let [cols (layout/column layout attr)]
    (when (nil? cols) (throw (ex-info "unknown particle attribute" {:attr attr})))
    (when (not= 1 (count cols)) (throw (ex-info "attribute is not a scalar float column" {:attr attr :cols cols})))
    (long (first cols))))

(defn- vec3-cols ^longs [layout attr]
  (let [cols (layout/column layout attr)]
    (when (not= 3 (count (or cols [])))
      (throw (ex-info "attribute is not a :vec3 column" {:attr attr :cols cols})))
    (long-array cols)))

;; --- module compilation -----------------------------------------------------
;;
;; Every compiled module is (fn [^ParticleColumns pc ^long from ^long to
;; ^double dt] ...); spawn/init modules are called once, over the range
;; ParticleColumns/reserve just returned; update modules are called once
;; per tick over [0, pc.size()).

(defmulti compile-module (fn [decl _layout _context] (:module decl)))

(defmethod compile-module :location/line [decl layout context]
  (let [[fx fy fz] (vec3-of (resolve-field (:from decl) context))
        [tx ty tz] (vec3-of (resolve-field (:to decl) context))
        cap (long (:capacity layout))
        [px py pz] (vec3-cols layout :position)]
    (fn [^ParticleColumns pc ^long from ^long to ^double _dt]
      (let [^floats a (.floats pc) n (max 1.0 (double (- to from)))]
        (loop [i from]
          (when (< i to)
            (let [t (/ (double (- i from)) n)]
              (aset a (+ (* px cap) i) (float (+ fx (* t (- tx fx)))))
              (aset a (+ (* py cap) i) (float (+ fy (* t (- ty fy)))))
              (aset a (+ (* pz cap) i) (float (+ fz (* t (- tz fz))))))
            (recur (unchecked-inc i))))))))

(defmethod compile-module :velocity/const [decl layout context]
  (let [[vx vy vz] (vec3-of (resolve-field (:value decl) context))
        cap (long (:capacity layout))
        [cvx cvy cvz] (vec3-cols layout :velocity)]
    (fn [^ParticleColumns pc ^long from ^long to ^double _dt]
      (let [^floats a (.floats pc)]
        (loop [i from]
          (when (< i to)
            (aset a (+ (* cvx cap) i) (float vx))
            (aset a (+ (* cvy cap) i) (float vy))
            (aset a (+ (* cvz cap) i) (float vz))
            (recur (unchecked-inc i))))))))

(defmethod compile-module :set [decl layout context]
  (let [value (double (resolve-field (:value decl) context))
        cap (long (:capacity layout))
        col (float-col layout (:attr decl))]
    (fn [^ParticleColumns pc ^long from ^long to ^double _dt]
      (let [^floats a (.floats pc)]
        (loop [i from]
          (when (< i to)
            (aset a (+ (* col cap) i) (float value))
            (recur (unchecked-inc i))))))))

(defmethod compile-module :forces/apply [decl layout _context]
  (let [[gx gy gz] (vec3-of (or (:gravity decl) [0.0 0.0 0.0]))
        drag (double (or (:drag decl) 0.0))
        cap (long (:capacity layout))
        [cvx cvy cvz] (vec3-cols layout :velocity)]
    (fn [^ParticleColumns pc ^long from ^long to ^double dt]
      (let [^floats a (.floats pc) damp (float (max 0.0 (- 1.0 (* drag dt))))]
        (loop [i from]
          (when (< i to)
            (let [ox (+ (* cvx cap) i) oy (+ (* cvy cap) i) oz (+ (* cvz cap) i)]
              (aset a ox (* damp (+ (aget a ox) (float (* gx dt)))))
              (aset a oy (* damp (+ (aget a oy) (float (* gy dt)))))
              (aset a oz (* damp (+ (aget a oz) (float (* gz dt))))))
            (recur (unchecked-inc i))))))))

(defmethod compile-module :integrate [_decl layout _context]
  (let [cap (long (:capacity layout))
        [px py pz] (vec3-cols layout :position)
        [vx vy vz] (vec3-cols layout :velocity)
        age-col (float-col layout :age)]
    (fn [^ParticleColumns pc ^long from ^long to ^double dt]
      (let [^floats a (.floats pc) dtf (float dt)]
        (loop [i from]
          (when (< i to)
            (aset a (+ (* px cap) i) (+ (aget a (+ (* px cap) i)) (* dtf (aget a (+ (* vx cap) i)))))
            (aset a (+ (* py cap) i) (+ (aget a (+ (* py cap) i)) (* dtf (aget a (+ (* vy cap) i)))))
            (aset a (+ (* pz cap) i) (+ (aget a (+ (* pz cap) i)) (* dtf (aget a (+ (* vz cap) i)))))
            (aset a (+ (* age-col cap) i) (+ (aget a (+ (* age-col cap) i)) dtf))
            (recur (unchecked-inc i))))))))

(defn- lerp-points
  "Piecewise-linear interpolation of `points` ([[t v] ...], t in [0,1],
   sorted ascending) at fraction `t`. Clamps to the first/last point
   outside the declared range."
  ^double [points ^double t]
  (let [pts (vec (sort-by first points))]
    (cond
      (empty? pts) 0.0
      (<= t (double (ffirst pts))) (double (second (first pts)))
      (>= t (double (first (peek pts)))) (double (second (peek pts)))
      :else
      (loop [i 0]
        (let [[t0 v0] (nth pts i) [t1 v1] (nth pts (inc i))]
          (if (<= t0 t t1)
            (let [span (max 1e-9 (- (double t1) (double t0)))]
              (+ (double v0) (* (/ (- t (double t0)) span) (- (double v1) (double v0)))))
            (recur (inc i))))))))

(defmethod compile-module :curve [decl layout _context]
  (let [points (:points decl)
        cap (long (:capacity layout))
        target-col (float-col layout (:attr decl))
        age-col (float-col layout :age)
        life-col (float-col layout :lifetime)]
    (fn [^ParticleColumns pc ^long from ^long to ^double _dt]
      (let [^floats a (.floats pc)]
        (loop [i from]
          (when (< i to)
            (let [age (aget a (+ (* age-col cap) i))
                  life (max 1e-6 (aget a (+ (* life-col cap) i)))
                  progress (min 1.0 (max 0.0 (/ (double age) (double life))))]
              (aset a (+ (* target-col cap) i) (float (lerp-points points progress))))
            (recur (unchecked-inc i))))))))

;; kill-expired mutates pc's size via swapRemove, invalidating indices
;; above the removed one -- iterate the range BACKWARD so an in-progress
;; swap never disturbs an index not yet visited.
(defmethod compile-module :kill-expired [_decl layout _context]
  (let [cap (long (:capacity layout))
        float-cols (long (:float-cols layout))
        int-cols (long (:int-cols layout))
        age-col (float-col layout :age)
        life-col (float-col layout :lifetime)]
    (fn [^ParticleColumns pc ^long _from ^long to ^double _dt]
      (let [^floats a (.floats pc)]
        (loop [i (dec to)]
          (when (>= i 0)
            (when (>= (aget a (+ (* age-col cap) i)) (aget a (+ (* life-col cap) i)))
              (.swapRemove pc (int i) (int float-cols) (int int-cols)))
            (recur (dec i))))))))

(defn compile-spawn-stage
  "The compiled closure for one spawn-stage module list: reserves
   :spawn/burst's :count new slots, then runs every remaining module
   (assumed :location/line, :velocity/const, :set, ...) over exactly that
   new range. Returns (fn [^ParticleColumns pc dt] -> nil)."
  [modules layout context]
  (let [count-decl (first (filter #(= :spawn/burst (:module %)) modules))
        n (long (or (:count count-decl) 0))
        init-fns (mapv #(compile-module % layout context) (remove #(= :spawn/burst (:module %)) modules))]
    (fn [^ParticleColumns pc ^double dt]
      (when (pos? n)
        (let [from (long (.reserve pc (int n))) to (+ from n)]
          (doseq [f init-fns] (f pc from to dt)))))))

(defn compile-update-stage
  "modules run in order over the WHOLE live range each tick; kill-expired,
   if present, must be listed last (it shrinks the range other modules
   already finished reading/writing over)."
  [modules layout context]
  (let [fns (mapv #(compile-module % layout context) modules)]
    (fn [^ParticleColumns pc ^double dt]
      (let [to (long (.size pc))]
        (doseq [f fns] (f pc 0 to dt))))))

(defn compile-emitter
  "decl: {:id :capacity :attrs {attr-name type} :spawn [...] :update [...]}.
   context: {k already-resolved-value}, e.g. this effect instance's :user
   capability values, resolved once by the caller before compiling (see
   this namespace's docstring, scope cut 1).
   Returns {:layout ... :spawn (fn [pc dt]) :update (fn [pc dt])
            :new-buffer (fn [] a fresh ParticleColumns)}."
  [decl context]
  (let [layout (layout/build (:attrs decl) (long (:capacity decl)))]
    {:layout layout
     :spawn (compile-spawn-stage (:spawn decl) layout context)
     :update (compile-update-stage (:update decl) layout context)
     :new-buffer (fn [] (ParticleColumns. (int (:capacity layout))
                                          (int (:float-cols layout))
                                          (int (:int-cols layout))))}))
