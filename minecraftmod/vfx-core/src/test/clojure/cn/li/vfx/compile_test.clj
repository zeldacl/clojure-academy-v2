(ns cn.li.vfx.compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.compile :as compile]
            [cn.li.vfx.layout :as layout]))

(def ^:private sparks-decl
  {:id :sparks :capacity 64
   :attrs {:position :vec3 :velocity :vec3 :age :float :lifetime :float :size :float}
   :spawn [{:module :spawn/burst :count 4}
          {:module :spawn/set :attr :position
           :value {:kind :line :from [:context :start] :to [:context :end]}}
          {:module :spawn/set :attr :velocity :value [0.0 0.05 0.0]}
          {:module :spawn/set :attr :lifetime :value 2.0}
          {:module :spawn/set :attr :size :value 0.1}]
   :update [{:module :forces/apply :gravity [0.0 -0.02 0.0] :drag 0.1}
           {:module :integrate}
           {:module :curve :attr :size :points [[0.0 0.02] [1.0 0.0]]}
           {:module :kill-expired}]})

(def ^:private context {:start [0.0 0.0 0.0] :end [4.0 0.0 0.0]})

(defn- component-values
  "Values for column index `c` (one component of a possibly multi-column
   attribute, e.g. (second (layout/column layout :velocity)) for
   velocity.y) across particles [0,n)."
  [pc layout c n]
  (let [cap (:capacity layout) a (.floats pc)]
    (mapv #(aget ^floats a (+ (* (long c) cap) %)) (range n))))

(defn- col-values
  "Scalar (single-column) attribute values across particles [0,n)."
  [pc layout attr n]
  (component-values pc layout (first (layout/column layout attr)) n))

(deftest spawn-reserves-and-initializes-particles-test
  (let [{:keys [layout spawn burst new-buffer]} (compile/compile-emitter sparks-decl context)
        pc (new-buffer)]
    (spawn pc burst 0.1)
    (testing "spawn/burst reserved exactly :count particles"
      (is (= 4 (.size pc))))
    (testing "location/line spreads position.x evenly across [start,end) -- last particle does
              not reach :end, matching t=i/n for i in 0..n-1"
      (is (= [0.0 1.0 2.0 3.0] (col-values pc layout :position 4))))
    (testing "velocity/const and set wrote the same value to every spawned particle"
      (let [[_ vy-col] (layout/column layout :velocity)]
        (is (every? #(< (Math/abs (- 0.05 %)) 1e-6) (component-values pc layout vy-col 4))))
      (is (every? #(< (Math/abs (- 2.0 %)) 1e-6) (col-values pc layout :lifetime 4)))
      (is (every? #(< (Math/abs (- 0.1 %)) 1e-6) (col-values pc layout :size 4))))
    (testing "age starts at zero (fresh float[] is zero-initialized, no explicit :set needed)"
      (is (every? zero? (col-values pc layout :age 4))))))

(deftest update-integrates-and-expires-on-schedule-test
  (let [{:keys [layout spawn burst update new-buffer]} (compile/compile-emitter sparks-decl context)
        pc (new-buffer)]
    (spawn pc burst 0.1)
    (update pc 1.0)
    (testing "one tick: age advanced, still alive (lifetime 2.0)"
      (is (= 4 (.size pc)))
      (is (every? #(= 1.0 (double %)) (col-values pc layout :age 4))))
    (testing "gravity+drag moved particles off the spawn plane"
      (let [[_ py-col] (layout/column layout :position)]
        (is (every? #(> % 0.0) (component-values pc layout py-col 4))
            "position.y (velocity/const's initial 0.05 damped by drag, then integrated) must be positive")))
    (testing "curve interpolated size at progress=age/lifetime=0.5"
      (doseq [v (col-values pc layout :size 4)]
        (is (< (Math/abs (- 0.01 v)) 1e-4))))
    (update pc 1.0)
    (testing "second tick: age == lifetime, kill-expired removes every particle"
      (is (= 0 (.size pc))))))

(deftest kill-expired-uses-backward-iteration-so-swap-remove-cannot-skip-a-particle-test
  (testing "a mix of already-expired and fresh particles in one buffer: every
            expired one is removed, every fresh one survives, regardless of
            index order after swapRemove's swap-with-last semantics"
    (let [decl (assoc sparks-decl :capacity 8)
          {:keys [layout spawn burst update new-buffer]} (compile/compile-emitter decl context)
          pc (new-buffer)]
      (spawn pc burst 0.1)
      (spawn pc burst 0.1)
      (is (= 8 (.size pc)))
      ;; Force half the buffer to already be past its lifetime before ticking.
      (let [[age-col] (layout/column layout :age)
            [life-col] (layout/column layout :lifetime)
            cap (:capacity layout)
            a (.floats pc)]
        (dotimes [i 4]
          (aset ^floats a (+ (* age-col cap) i) (float 999.0)))
        (is (every? #(< % 999.0) (subvec (col-values pc layout :age 8) 4))))
      (update pc 0.001)
      (is (= 4 (.size pc)) "exactly the 4 pre-expired particles were removed"))))

;; --- spawn value generators -------------------------------------------------
;;
;; The module vocabulary used to be one module per (attribute x value
;; source): :location/line wrote :position, :velocity/const wrote
;; :velocity, :set wrote a constant anywhere. Saying two new things -- a
;; positional spread and a per-axis velocity range -- took three new
;; modules under that shape. These exercise the generators that replaced
;; it, where the :value's shape alone decides how the value is produced.

(defn- spawn-once
  "Run one emitter's spawn stage and return its buffer + layout."
  [decl context]
  (let [{:keys [layout spawn burst new-buffer]} (compile/compile-emitter decl context)
        pc (new-buffer)]
    (spawn pc burst 0.05)
    [pc layout]))

(def ^:private scatter-decl
  {:id :scatter :capacity 32 :seed 7
   :attrs {:position :vec3 :velocity :vec3 :alpha :float :lifetime :float}
   :spawn [{:module :spawn/burst :count 8}
           {:module :spawn/set :attr :position
            :value {:kind :box :center [10.0 0.0 -5.0]
                    :spread {:x [-1.0 1.0] :y [0.0 1.4] :z [-1.0 1.0]}}}
           {:module :spawn/set :attr :velocity
            :value {:kind :range :x [-0.03 0.03] :y [0.0 0.05] :z [-0.03 0.03]}}
           {:module :spawn/set :attr :alpha :value {:kind :uniform :min 153.0 :max 204.0}}
           {:module :spawn/set :attr :lifetime :value 20.0}]
   :update []})

(deftest box-spread-scatters-within-its-ranges-test
  (let [[pc layout] (spawn-once scatter-decl {})
        [cx cy cz] (layout/column layout :position)
        xs (component-values pc layout cx 8)
        ys (component-values pc layout cy 8)
        zs (component-values pc layout cz 8)]
    (testing "every particle lands inside the declared box"
      (is (every? #(<= 9.0 % 11.0) xs))
      (is (every? #(<= 0.0 % 1.4) ys))
      (is (every? #(<= -6.0 % -4.0) zs)))
    (testing "and they are NOT all at the centre -- a spread that collapsed
              to one point would satisfy the bounds above"
      (is (< 1 (count (distinct xs))))
      (is (< 1 (count (distinct ys)))))))

(deftest per-axis-velocity-range-is-asymmetric-test
  ;; The reason ranges replaced a symmetric magnitude: the teleport
  ;; marker's particles drift sideways either way but only ever rise.
  (let [[pc layout] (spawn-once scatter-decl {})
        [_ vy _] (layout/column layout :velocity)
        ys (component-values pc layout vy 8)]
    (is (every? #(<= 0.0 % 0.05) ys))
    (is (some pos? ys) "a range of [0 0.05] that produced only zeroes is not a range")))

(deftest uniform-scalar-differs-between-particles-test
  (let [[pc layout] (spawn-once scatter-decl {})
        alphas (col-values pc layout :alpha 8)]
    (is (every? #(<= 153.0 % 204.0) alphas))
    (is (< 1 (count (distinct alphas)))
        "every particle taking the same value would make the range pointless")))

(deftest spawn-is-deterministic-across-runs-test
  ;; The same emitter must produce the same particles on every client and
  ;; on every replay of a frame.
  (let [[a la] (spawn-once scatter-decl {})
        [b lb] (spawn-once scatter-decl {})]
    (is (= (col-values a la :alpha 8) (col-values b lb :alpha 8))))
  (testing "and a different emitter seed produces different particles"
    (let [[a la] (spawn-once scatter-decl {})
          [b lb] (spawn-once (assoc scatter-decl :seed 99) {})]
      (is (not= (col-values a la :alpha 8) (col-values b lb :alpha 8))))))

(deftest two-modules-of-one-stage-do-not-move-in-lockstep-test
  ;; Modules are salted by their position, so two ranges over the same
  ;; bounds must not produce the identical sequence.
  (let [decl {:id :twin :capacity 16 :seed 3
              :attrs {:alpha :float :size :float}
              :spawn [{:module :spawn/burst :count 6}
                      {:module :spawn/set :attr :alpha :value {:kind :uniform :min 0.0 :max 1.0}}
                      {:module :spawn/set :attr :size :value {:kind :uniform :min 0.0 :max 1.0}}]
              :update []}
        [pc layout] (spawn-once decl {})]
    (is (not= (col-values pc layout :alpha 6)
              (col-values pc layout :size 6)))))

(deftest an-unknown-generator-is-rejected-rather-than-ignored-test
  (is (thrown? clojure.lang.ExceptionInfo
               (compile/compile-emitter
                {:id :bad :capacity 4 :attrs {:alpha :float}
                 :spawn [{:module :spawn/burst :count 1}
                         {:module :spawn/set :attr :alpha :value {:kind :no-such-thing}}]
                 :update []}
                {}))))
