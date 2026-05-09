(ns cn.li.mc1201.runtime.vec-math)

(defn v+
  [a b]
  {:x (+ (:x a) (:x b))
   :y (+ (:y a) (:y b))
   :z (+ (:z a) (:z b))})

(defn v-
  [a b]
  {:x (- (:x a) (:x b))
   :y (- (:y a) (:y b))
   :z (- (:z a) (:z b))})

(defn v*
  [a s]
  {:x (* (:x a) s)
   :y (* (:y a) s)
   :z (* (:z a) s)})

(defn cross
  [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn normalize
  [v]
  (let [len (Math/sqrt (+ (* (:x v) (:x v))
                          (* (:y v) (:y v))
                          (* (:z v) (:z v))))]
    (if (< len 1.0e-6)
      {:x 0.0 :y 1.0 :z 0.0}
      (v* v (/ 1.0 len)))))