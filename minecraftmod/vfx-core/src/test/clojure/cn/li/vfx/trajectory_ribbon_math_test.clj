(ns cn.li.vfx.trajectory-ribbon-math-test
  "Verifies the closed-form per-index formula used by
   ac/vfx/composites_v3/trajectory_ribbon.edn (:vfx.fx/trajectory-ribbon)
   against the ORIGINAL imperative per-tick loop from the old :vfx/
   trajectory-ribbon Clojure component (vm.clj:778), before that formula
   ever gets encoded into EDN expr nodes -- porting a physics recurrence
   into a handful of nested {:expr ...} forms with no way to run the game
   is exactly the kind of change that needs an independent numeric proof,
   not just careful reading.

   The recurrence per original step (drag applied before gravity, position
   integrated from the POST-decay velocity):
     v' = v * drag
     v'.y = v'.y - dt*gravity
     p' = p + v' * dt
   emitting p BEFORE each step's update (points[0] = initial position).

   IMPORTANT, and the reason this file exists instead of trusting the
   algebra by inspection: position at step n+1 is integrated using the
   velocity AFTER drag decay but BEFORE this same step's gravity
   subtraction (py2 uses vy2, and only vy3 = vy2 - dt*gravity carries
   gravity into the NEXT iteration's velocity) -- gravity's effect on
   POSITION is delayed by one step relative to its effect on velocity. A
   first attempt at this closed form got that off by one and every
   gravity-affected assertion below failed until it was corrected; the
   no-gravity (x/z axis) assertions passed on the first try because they
   never exercise the delayed term.

   Closed form (drag != 1), with v_in[k] = drag^k*vy0 - dt*gravity*S(k)
   (S(n) = sum_{k=0}^{n-1} drag^k = (1-drag^n)/(1-drag)) the velocity
   value AT THE START of step k (used for position, not yet gravity-
   decremented), and U(n) = sum_{k=0}^{n-1} S(k) = (n - S(n)) / (1-drag):
     v.x[n] = vx0 * drag^n                 v.z[n] analogous
     p.x[n] = px0 + dt*drag*vx0*S(n)       p.z[n] analogous
     p.y[n] = py0 + dt*drag*vy0*S(n) - dt*dt*drag*gravity*U(n)
   (drag == 1 collapses to S(n)=n, U(n)=n*(n-1)/2, tested separately.)"
  (:require [clojure.test :refer [deftest is]]))

(defn- original-loop
  "Direct copy of vm.clj's :vfx/trajectory-ribbon inner loop (1D per axis,
   generalized to any of x/y/z by passing gravity 0.0 for x/z)."
  [p0 v0 drag gravity dt segments]
  (loop [idx 0 p (double p0) v (double v0) acc (transient [])]
    (if (>= idx segments)
      (persistent! acc)
      (let [v2 (* v drag)
            p2 (+ p (* v2 dt))
            v3 (- v2 (* dt gravity))]
        (recur (inc idx) p2 v3 (conj! acc p))))))

(defn- s-of [drag n]
  (if (= drag 1.0) (double n) (/ (- 1.0 (Math/pow drag n)) (- 1.0 drag))))

(defn- u-of [drag n]
  (if (= drag 1.0)
    (/ (* (double n) (dec n)) 2.0)
    (/ (- n (s-of drag n)) (- 1.0 drag))))

(defn- closed-form-no-gravity [p0 v0 drag dt n]
  (+ p0 (* dt drag v0 (s-of drag n))))

(defn- closed-form-with-gravity [p0 v0 drag gravity dt n]
  (- (+ p0 (* dt drag v0 (s-of drag n)))
     (* dt dt drag gravity (u-of drag n))))

(defn- approx= [a b] (< (Math/abs (- (double a) (double b))) 1.0e-6))

(deftest closed-form-matches-original-loop-no-drag-decay-test
  (doseq [[p0 v0 dt segs] [[0.0 5.0 0.02 20] [10.0 -3.0 0.05 10] [1.0 0.0 0.02 30]]]
    (let [expected (original-loop p0 v0 1.0 0.0 dt segs)
          actual (mapv #(closed-form-no-gravity p0 v0 1.0 dt %) (range segs))]
      (is (every? true? (map approx= expected actual))
          (str "drag=1.0 no-gravity mismatch for p0=" p0 " v0=" v0)))))

(deftest closed-form-matches-original-loop-with-drag-test
  (doseq [[p0 v0 drag dt segs] [[0.0 5.0 0.9 0.02 20] [10.0 -3.0 0.7 0.05 10] [1.0 8.0 0.99 0.02 30]]]
    (let [expected (original-loop p0 v0 drag 0.0 dt segs)
          actual (mapv #(closed-form-no-gravity p0 v0 drag dt %) (range segs))]
      (is (every? true? (map approx= expected actual))
          (str "no-gravity mismatch for p0=" p0 " v0=" v0 " drag=" drag)))))

(deftest closed-form-matches-original-loop-with-gravity-and-drag-test
  (doseq [[p0 v0 drag gravity dt segs] [[64.0 2.0 0.9 20.0 0.02 20]
                                         [0.0 -1.0 0.7 9.8 0.05 15]
                                         [5.0 0.0 0.95 25.0 0.02 40]]]
    (let [expected (original-loop p0 v0 drag gravity dt segs)
          actual (mapv #(closed-form-with-gravity p0 v0 drag gravity dt %) (range segs))]
      (is (every? true? (map approx= expected actual))
          (str "gravity mismatch for p0=" p0 " v0=" v0 " drag=" drag " gravity=" gravity)))))

(deftest closed-form-matches-original-loop-with-gravity-drag-one-test
  ;; drag == 1.0 hits the S(n)=n / T(n)=n(n+1)/2 branch, not the geometric
  ;; series division -- a real edge case (1/(1-drag) would divide by zero).
  (doseq [[p0 v0 gravity dt segs] [[64.0 2.0 20.0 0.02 20] [0.0 -1.0 9.8 0.05 15]]]
    (let [expected (original-loop p0 v0 1.0 gravity dt segs)
          actual (mapv #(closed-form-with-gravity p0 v0 1.0 gravity dt %) (range segs))]
      (is (every? true? (map approx= expected actual))
          (str "drag=1.0 gravity mismatch for p0=" p0 " v0=" v0 " gravity=" gravity)))))
