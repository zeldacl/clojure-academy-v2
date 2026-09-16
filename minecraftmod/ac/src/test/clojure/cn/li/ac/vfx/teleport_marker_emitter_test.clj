(ns cn.li.ac.vfx.teleport-marker-emitter-test
  "teleport-marker's shipped :emitters declaration, run for real.

   This is the first effect on the Niagara path, so it is also the proof
   that the path carries content at all: the declaration is read off the
   shipped resource, compiled by cn.li.vfx.compile and ticked by
   cn.li.vfx.runtime, and the particles are checked against the pre-V4
   original's own numbers rather than against the declaration (which would
   only prove the file parses).

   The original is EntityTPMarking + TPParticleFactory: on each tick, if
   the marker is available, a 40% roll spawns one particle at
   anchor + (rand(-1,1), rand(0.2,1.6) - 1.6, rand(-1,1)) with velocity
   (rand(-.03,.03), rand(0,.05), rand(-.03,.03)) per tick, size 0.1-0.2,
   white at alpha 153-204, holding full alpha for 20 ticks and then fading
   out over 20 more.

   Two unit conversions are the easy place for this to go quietly wrong,
   so both are asserted below rather than trusted: the columns integrate in
   SECONDS, so a per-tick velocity is 20x its per-second value, and the
   40%-per-tick roll is a rate of 8 particles per second.

   Spawn distributions are read from a spawn-only buffer, never from a
   ticked one. A ticked buffer has already integrated every particle by its
   own age, so `every particle is within 1 block of the anchor` would be
   testing the drift and the spawn box together and failing on both."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cn.li.vfx.compile :as vfx-compile]
            [cn.li.vfx.layout :as layout]
            [cn.li.vfx.runtime :as runtime]))

(def ^:private anchor [10.0 64.0 -5.0])

(defn- decl []
  (let [doc (edn/read-string (slurp (io/resource "ac/vfx-v4/teleport-marker.edn")))]
    (is (= 1 (count (:emitters doc)))
        "teleport-marker must declare exactly one emitter")
    (first (:emitters doc))))

(defn- spawned
  "n particles straight out of the shipped spawn stage -- no integration,
   no expiry. Returns {attr [[c0 ...] [c1 ...] [c2 ...]]}, one inner vector
   per column of the attribute."
  [n]
  (let [d (decl)
        {:keys [spawn new-buffer layout]}
        (vfx-compile/compile-emitter d {:position anchor :particle-rate 8.0})
        pc (new-buffer)
        cap (long (:capacity layout))]
    (spawn pc n 0.05)
    (into {}
          (map (fn [[attr cols]]
                 [attr (mapv (fn [c]
                               (mapv #(double (aget ^floats (.floats pc)
                                                    (+ (* (long c) cap) (long %))))
                                     (range n)))
                             cols)]))
          (:cols layout))))

(defn- ticked
  "The live buffer after `n` ticks of 0.05s at `rate` particles/second."
  ([n] (ticked n 8.0))
  ([n rate]
   (let [store (runtime/create-store {:teleport-marker {:emitters [(decl)]}})]
     (runtime/ensure! store [:k] {:effect-id :teleport-marker :seed 1
                                  :user {:position anchor :particle-rate rate}})
     (dotimes [_ n] (runtime/tick! store 0.05))
     (-> (runtime/lookup store [:k]) :emitters first :buffer))))

(deftest emits-at-mains-rate-test
  (testing "0.4 particles per tick, accumulated across ticks"
    ;; Checked before anything can expire (lifetime is 40 ticks), so this
    ;; is emission alone rather than emission minus death.
    (is (= 0 (.size (ticked 1))))
    (is (= 12 (.size (ticked 30)))))
  (testing "a rate of 0 emits nothing, which is how content stops the
            marker when the destination is blocked -- main's `available`"
    ;; penetrate-teleport ships :particle-rate 0.0 for exactly this.
    (is (= 0 (.size (ticked 400 0.0))))))

(deftest population-settles-at-rate-times-lifetime-test
  ;; 8/s for 2s is 16 live particles. It settles at 17 because a tick
  ;; spawns after it expires, so the newest particle is always younger
  ;; than one full tick.
  (is (= 17 (.size (ticked 400))))
  (is (= 17 (.size (ticked 1000))) "and stays there rather than growing"))

(deftest particles-spawn-in-mains-offset-box-test
  (let [[xs ys zs] (:position (spawned 200))]
    (testing "x and z scatter +-1 around the anchor"
      (is (every? #(<= (- (nth anchor 0) 1.0) % (+ (nth anchor 0) 1.0)) xs))
      (is (every? #(<= (- (nth anchor 2) 1.0) % (+ (nth anchor 2) 1.0)) zs))
      (is (neg? (- (apply min xs) (nth anchor 0))) "scatters both ways")
      (is (pos? (- (apply max xs) (nth anchor 0)))))
    (testing "y spans rand(0.2,1.6) - 1.6, i.e. entirely BELOW the anchor --
              the motes rise toward the marker rather than falling from it"
      ;; Asserted as a band, not a bound: a symmetric spread would also
      ;; satisfy `<= anchor` for half its particles.
      (is (every? #(<= (- (nth anchor 1) 1.4) % (nth anchor 1)) ys))
      (is (< (apply min ys) (- (nth anchor 1) 1.3)) "the low end is reached")
      (is (> (apply max ys) (- (nth anchor 1) 0.1)) "and so is the high end"))))

(deftest velocity-is-mains-per-tick-drift-converted-to-seconds-test
  (let [[vx vy vz] (:velocity (spawned 200))]
    (testing "sideways drift is symmetric, +-0.03/tick = +-0.6/s"
      (is (every? #(<= -0.6 % 0.6) vx))
      (is (every? #(<= -0.6 % 0.6) vz))
      (is (neg? (apply min vx)) "drifts both ways, not just positive")
      (is (pos? (apply max vz))))
    (testing "vertical drift only ever rises, 0..0.05/tick = 0..1.0/s"
      (is (every? #(<= 0.0 % 1.0) vy))
      (is (pos? (apply max vy)) "and is not simply always zero"))))

(deftest size-and-alpha-vary-per-particle-test
  (let [s (spawned 200)
        sizes (first (:size s))
        alphas (first (:alpha s))]
    (is (every? #(<= 0.1 % 0.2) sizes))
    (is (every? #(<= 153.0 % 204.0) alphas))
    (testing "and genuinely differ -- one shared roll would render as one
              uniform cloud instead of main's speckle"
      (is (< 100 (count (distinct sizes))))
      (is (< 100 (count (distinct alphas)))))))

(deftest a-continuously-emitted-particle-is-not-a-copy-of-the-last-test
  ;; The failure this guards is specific to continuous emission and
  ;; invisible to a one-shot burst: kill-expired swap-removes, so once the
  ;; population settles, reserve hands out the same slot every time. Seeded
  ;; on the slot, every particle spawned from then on was identical.
  (let [pc (ticked 1000)
        d (decl)
        l (layout/build (:attrs d) (:capacity d))
        cap (long (:capacity l))
        col (fn [attr] (mapv #(double (aget ^floats (.floats pc)
                                            (+ (* (long (first (layout/column l attr))) cap)
                                               (long %))))
                             (range (.size pc))))]
    (is (< 10 (count (distinct (col :size))))
        "the live population is one repeated particle, not a speckle")
    (is (< 10 (count (distinct (col :alpha)))))))

(deftest particles-expire-on-mains-schedule-test
  ;; fadeAfter(20, 20): full alpha for 20 ticks, fading for 20 more, dead
  ;; at 40 -- a lifetime of 2.0 seconds.
  (let [lifetimes (first (:lifetime (spawned 50)))]
    (is (every? #(= 2.0 %) lifetimes)))
  (testing "and the material fades over the last 20 of those 40 ticks"
    (let [m (:material (decl))]
      (is (= 1.0 (get-in m [:particle :fade-out])))
      (is (nil? (get-in m [:particle :fade-in]))
          "main holds full alpha from spawn -- there is no ramp in"))))
