(ns cn.li.ac.vfx.generic-emitter-parameters-test
  "particle-session / particle-burst take their whole particle from typed
   parameters, and every one of them reaches the particles.

   They used to take one opaque :particle map of type :any, and the renderer
   read six of its keys -- so :speed, :spread, :velocity and :material, the
   entire motion and blending of nine skills, were dropped between the scene
   op and the screen. Every caller compiled, every caller rendered a static
   blob at its anchor, and nothing failed.

   The parameters are therefore checked by VALUE here, per parameter. Two
   of them can fail in ways no shape assertion would catch:

   - the material's fields are [:context k] references resolved at compile
     time, and an unresolved one arrives at the renderer as a literal
     two-element vector, visible only as a missing texture;
   - the drive parameter differs by lifecycle (:rate for the session,
     :count for the burst), because a continuous emitter and a one-shot
     burst are genuinely different things rather than one with the other's
     value set to zero."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.compile :as vfx-compile]
            [cn.li.vfx.layout :as layout]))

(defn- decl [effect]
  (let [doc (edn/read-string (slurp (io/resource (str "ac/vfx-v4/" effect ".edn"))))]
    (is (= 1 (count (:emitters doc))) (str effect " must declare one emitter"))
    (first (:emitters doc))))

(def ^:private payload
  "A payload in the shape skills-v4 now passes -- flat, typed, no nested
   particle map. The numbers are meltdowner's and light-shield's: a tinted
   cloud around a body with a rising bias."
  {:position [10.0 64.0 -5.0]
   :direction [0.0 0.0 0.0]
   :distance-min 0.0 :distance-max 0.0
   :spread [0.7 0.7 0.7]
   :velocity [0.4 0.6 0.4]
   :velocity-bias [0.0 0.4 0.0]
   :texture "academy:textures/effects/md_particle.png"
   :color [106 242 106 150]
   :size-min 0.05 :size-max 0.07
   :alpha-min 76.0 :alpha-max 152.0
   :life-min 2.25 :life-max 3.75
   :fade-out 1.0
   :rate 80.0 :count 12})

(defn- compiled [effect]
  (let [d (decl effect)
        c (vfx-compile/compile-emitter d payload)]
    (assoc c :n 200)))

(defn- values
  "{attr [[c0 ...] ...]} after spawning n particles."
  [{:keys [spawn new-buffer layout n]}]
  (let [pc (new-buffer)
        cap (long (:capacity layout))]
    (spawn pc n 0.05)
    (into {}
          (map (fn [[attr cols]]
                 [attr (mapv (fn [c]
                               (mapv #(double (aget ^floats (.floats pc)
                                                    (+ (* (long c) cap) (long %))))
                                     (range (min n (.size pc)))))
                             cols)]))
          (:cols layout))))

(deftest material-context-references-are-resolved-test
  (doseq [effect ["particle-session" "particle-burst"]]
    (let [m (:material (vfx-compile/compile-emitter (decl effect) payload))
          p (:particle m)]
      (testing (str effect " resolves every [:context k] in its material")
        (is (= "academy:textures/effects/md_particle.png" (:texture p)))
        (is (= [106 242 106 150] (:color p)))
        (is (= 1.0 (:fade-out p))))
      (testing "and leaves no unresolved reference behind"
        (is (empty? (filter #(and (vector? %) (= :context (first %)))
                            (tree-seq coll? seq m)))
            (str "an unresolved [:context k] reaches the renderer as a"
                 " literal vector: " (pr-str m)))))))

(deftest the-drive-parameter-differs-by-lifecycle-test
  (testing "the session emits continuously and bursts nothing"
    (let [c (compiled "particle-session")]
      (is (= 0 (:burst c)))
      (is (= 80.0 (:rate c)))))
  (testing "the burst emits its :count once and never again"
    (let [c (compiled "particle-burst")]
      (is (= 12 (:burst c)))
      (is (= 0.0 (:rate c))))))

(deftest spawn-parameters-reach-the-particles-test
  (let [v (values (compiled "particle-session"))
        [xs ys zs] (:position v)
        [vx vy vz] (:velocity v)
        sizes (first (:size v))
        alphas (first (:alpha v))
        lifes (first (:lifetime v))
        anchor (:position payload)]
    (testing ":spread scatters around :position by its own half-extents"
      (is (every? #(<= (- (nth anchor 0) 0.7) % (+ (nth anchor 0) 0.7)) xs))
      (is (every? #(<= (- (nth anchor 1) 0.7) % (+ (nth anchor 1) 0.7)) ys))
      (is (every? #(<= (- (nth anchor 2) 0.7) % (+ (nth anchor 2) 0.7)) zs))
      (is (< 0.6 (- (apply max xs) (apply min xs)))
          "the spread is actually used, not collapsed to the anchor"))
    (testing ":velocity is symmetric, and :velocity-bias shifts one axis"
      (is (every? #(<= -0.4 % 0.4) vx))
      (is (every? #(<= -0.4 % 0.4) vz))
      (is (every? #(<= -0.2 % 1.0) vy))
      (is (pos? (apply min (map + vy (repeat 0.2))))
          "the bias lifts the whole y range, so nothing falls faster than -0.2"))
    (testing ":size-min/:size-max and :alpha-min/:alpha-max are ranges"
      (is (every? #(<= 0.05 % 0.07) sizes))
      (is (every? #(<= 76.0 % 152.0) alphas))
      (is (< 100 (count (distinct alphas)))))
    (testing ":life-min/:life-max give each particle its own lifetime"
      (is (every? #(<= 2.25 % 3.75) lifes))
      (is (< 100 (count (distinct lifes)))))))

(deftest a-direction-and-distance-place-particles-along-a-ray-test
  ;; The mine ray and the MD ray spawn along the player's look vector at a
  ;; random distance, which is why :scatter has a :direction at all. With
  ;; the default zero direction it must stay a plain box, or every existing
  ;; caller silently gains a drift.
  (let [along (assoc payload :direction [0.0 0.0 1.0]
                     :distance-min 0.0 :distance-max 10.0
                     :spread [0.0 0.0 0.0])
        d (decl "particle-session")
        run (fn [p] (values (assoc (vfx-compile/compile-emitter d p) :n 200)))
        zs (nth (:position (run along)) 2)
        base (nth (:position (run (assoc payload :spread [0.0 0.0 0.0]))) 2)
        z0 (nth (:position payload) 2)]
    (is (every? #(<= z0 % (+ z0 10.0)) zs))
    (is (< 8.0 (- (apply max zs) (apply min zs)))
        "the distance range spans most of its 10 blocks")
    (is (every? #(= z0 %) base)
        "with no direction and no spread every particle sits on the anchor")))
