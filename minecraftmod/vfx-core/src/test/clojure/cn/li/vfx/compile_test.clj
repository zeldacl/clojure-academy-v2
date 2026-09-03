(ns cn.li.vfx.compile-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.compile :as compile]
            [cn.li.vfx.layout :as layout]))

(def ^:private sparks-decl
  {:id :sparks :capacity 64
   :attrs {:position :vec3 :velocity :vec3 :age :float :lifetime :float :size :float}
   :spawn [{:module :spawn/burst :count 4}
          {:module :location/line :from [:context :start] :to [:context :end]}
          {:module :velocity/const :value [0.0 0.05 0.0]}
          {:module :set :attr :lifetime :value 2.0}
          {:module :set :attr :size :value 0.1}]
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
  (let [{:keys [layout spawn new-buffer]} (compile/compile-emitter sparks-decl context)
        pc (new-buffer)]
    (spawn pc 0.1)
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
  (let [{:keys [layout spawn update new-buffer]} (compile/compile-emitter sparks-decl context)
        pc (new-buffer)]
    (spawn pc 0.1)
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
          {:keys [layout spawn update new-buffer]} (compile/compile-emitter decl context)
          pc (new-buffer)]
      (spawn pc 0.1)
      (spawn pc 0.1)
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
