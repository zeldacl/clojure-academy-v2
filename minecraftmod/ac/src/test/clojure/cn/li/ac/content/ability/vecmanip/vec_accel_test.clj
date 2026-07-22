(ns cn.li.ac.content.ability.vecmanip.vec-accel-test
  "Unit tests for VecAccel skill logic.

  Tests cover:
    - compute-init-vel  (velocity computation, pitch clamp, speed scaling)
    - check-ground-raycast  (platform nil guards)
    - perform! action (launches player, skips when can-perform?=false)
    - :fx :end payload (reads [:skill-state :performed?], not root context)"
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.test.skill-callback-test-helpers :as cb]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.effects.motion :as motion-effects]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            ;; side-effectful require: ensures defskill runs and private fns are compiled
            [cn.li.ac.content.ability.vecmanip.vec-accel]))

;; ---------------------------------------------------------------------------
;; Helpers �?access private fns
;; ---------------------------------------------------------------------------

(def ^:private compute-init-vel
  @#'cn.li.ac.content.ability.vecmanip.vec-accel/compute-init-vel)

(def ^:private check-ground-raycast
  @#'cn.li.ac.content.ability.vecmanip.vec-accel/check-ground-raycast)

;; The skill spec map (public var created by defskill)
(def ^:private spec
  cn.li.ac.content.ability.vecmanip.vec-accel/vec-accel)

;; ---------------------------------------------------------------------------
;; Config mock helper
;; ---------------------------------------------------------------------------

;; Default config values matching vecmanip.clj defaults.
(defmacro ^:private with-config-mocks [& body]
  `(with-redefs [cn.li.ac.content.ability.vecmanip.vec-accel/cfg-double
                 (fn [field#]
                   (case field#
                     :movement.pitch-offset-radians   -0.174533
                     :movement.max-velocity           2.5
                     :targeting.groundless-exp-threshold 0.5
                     :targeting.ground-check-distance 2.0
                     :progression.exp-use             0.002
                     0.0))
                 cn.li.ac.content.ability.vecmanip.vec-accel/cfg-int
                 (fn [field#]
                   (case field#
                     :charge.max-ticks 20
                     0))
                 cn.li.ac.content.ability.vecmanip.vec-accel/cfg-lerp
                 (fn [field# _exp#]
                   (case field#
                     :cost.up.cp       120.0
                     :cost.up.overload  30.0
                     0.0))
                 cn.li.ac.content.ability.vecmanip.vec-accel/cfg-lerp-int
                 (fn [field# _exp#]
                   (case field#
                     :cooldown.ticks 80
                     0))
                 skill-config/lerp-double
                 (fn [_skill-id# field# t#]
                   (case field#
                     :movement.speed-progress (+ 0.4 (* 0.6 (double t#)))
                     0.0))]
     ~@body))

;; ---------------------------------------------------------------------------
;; 1. compute-init-vel �?horizontal look direction
;; ---------------------------------------------------------------------------

(deftest compute-init-vel-horizontal-test
  (testing "horizontal look (y=0, z=1): slight upward y, forward z, zero x"
    (with-config-mocks
      (let [look-dir {:x 0.0 :y 0.0 :z 1.0}
            vel      (compute-init-vel look-dir 10)]
        (is (< (Math/abs (double (:x vel))) 1.0e-6)
            "x velocity should be ~0 when look-x=0")
        (is (pos? (double (:y vel)))
            "y velocity should be positive (pitch offset tilts upward)")
        (is (pos? (double (:z vel)))
            "z velocity should be positive (forward)")))))

;; ---------------------------------------------------------------------------
;; 2. compute-init-vel �?straight up clamped to -π/2
;; ---------------------------------------------------------------------------

(deftest compute-init-vel-straight-up-clamp-test
  (testing "looking straight up: pitch clamped to -π/2, horizontal components �?0"
    (with-config-mocks
      (let [look-dir {:x 0.0 :y 1.0 :z 0.0}
            vel      (compute-init-vel look-dir 10)]
        (is (< (Math/abs (double (:x vel))) 1.0e-4)
            "x �?0 when clamped to vertical")
        (is (< (Math/abs (double (:z vel))) 1.0e-4)
            "z �?0 when clamped to vertical")
        (is (pos? (double (:y vel)))
            "y > 0: launching upward")))))

;; ---------------------------------------------------------------------------
;; 3. compute-init-vel �?speed scales with charge ticks
;; ---------------------------------------------------------------------------

(deftest compute-init-vel-speed-scales-with-charge-test
  (testing "full charge (20 ticks) produces higher speed than zero charge (0 ticks)"
    (with-config-mocks
      (let [look-dir  {:x 0.0 :y 0.0 :z 1.0}
            vel-zero  (compute-init-vel look-dir 0)
            vel-full  (compute-init-vel look-dir 20)
            speed-zero (Math/sqrt (+ (* (:x vel-zero) (:x vel-zero))
                                     (* (:y vel-zero) (:y vel-zero))
                                     (* (:z vel-zero) (:z vel-zero))))
            speed-full (Math/sqrt (+ (* (:x vel-full) (:x vel-full))
                                     (* (:y vel-full) (:y vel-full))
                                     (* (:z vel-full) (:z vel-full))))]
        (is (> speed-full speed-zero)
            "full-charge speed should exceed zero-charge speed")))))

;; ---------------------------------------------------------------------------
;; 4. check-ground-raycast �?no raycast platform (nil guard)
;; ---------------------------------------------------------------------------

(deftest check-ground-raycast-no-raycast-platform-test
  (testing "when raycast runtime is absent, returns nil without NPE"
    (raycast/call-with-runtime nil
                               (fn []
                                 (is (nil? (check-ground-raycast "player-1"))
                                           "nil teleportation runtime yields nil position")))))

;; ---------------------------------------------------------------------------
;; 5. check-ground-raycast �?no teleportation platform (nil guard)
;; ---------------------------------------------------------------------------

(deftest check-ground-raycast-no-teleport-position-test
  (testing "when teleportation runtime is absent, returns nil without NPE"
    (teleportation/call-with-runtime nil
                                     (fn []
                                       (is (nil? (check-ground-raycast "player-1"))
            "nil teleportation platform �?get-player-position returns nil �?result nil")))))

;; ---------------------------------------------------------------------------
;; 6. perform! �?launches player when can-perform? + init-vel both present
;; ---------------------------------------------------------------------------

(deftest perform-launches-player-test
  (testing "perform! calls set-velocity! and marks performed?=true when conditions met"
    (let [vel-calls    (atom [])
          update-calls (atom [])
          init-vel     {:x 1.0 :y 0.5 :z 0.0}
          test-ctx     {:skill-state {:can-perform? true :init-vel init-vel}}
          perform-fn   (get-in spec [:actions :perform!])]
      (with-config-mocks
        (with-redefs [ctx/get-context
                      (fn ([id] test-ctx)
                          ([_owner id] test-ctx))
                      ctx-skill/update-skill-state-root!
                      (fn [_id f & args] (swap! update-calls conj (apply list f args)))
                      motion-effects/player-motion-available? (constantly true)
                      motion-effects/set-player-velocity!
                      (fn [pid x y z] (swap! vel-calls conj {:player-id pid :x x :y y :z z}))
                      teleportation/available? (constantly true)
                      teleportation/reset-fall-damage!*
                      (fn [_pid] nil)
                      skill-effects/set-main-cooldown! (fn [& _] nil)
                      skill-effects/add-skill-exp!     (fn [& _] nil)]
          (cb/apply-invoke perform-fn :player-id "p1" :ctx-id "ctx-1" :exp 0.5)))
      (is (= 1 (count @vel-calls))
          "set-velocity! called exactly once")
      (is (= 1.0 (double (:x (first @vel-calls)))) "x velocity matches init-vel")
      (is (= 0.5 (double (:y (first @vel-calls)))) "y velocity matches init-vel")
      (is (= 0.0 (double (:z (first @vel-calls)))) "z velocity matches init-vel")
      (is (seq @update-calls)
          "ctx-skill/update-skill-state-root! called at least once"))))

;; ---------------------------------------------------------------------------
;; 7. perform! �?skips launch when can-perform? is false
;; ---------------------------------------------------------------------------

(deftest perform-skips-when-cannot-perform-test
  (testing "perform! does NOT call set-velocity! when can-perform?=false"
    (let [vel-calls (atom [])
          test-ctx  {:skill-state {:can-perform? false
                                   :init-vel     {:x 1.0 :y 0.0 :z 0.0}}}
          perform-fn (get-in spec [:actions :perform!])]
      (with-config-mocks
        (with-redefs [ctx/get-context            (fn ([id] test-ctx) ([_ id] test-ctx))
                      ctx-skill/assoc-skill-state!     (fn [& _] nil)
                      ctx-skill/update-skill-state-root! (fn [& _] nil)
                      motion-effects/player-motion-available? (constantly true)
                      motion-effects/set-player-velocity!
                      (fn [& _] (swap! vel-calls conj :called))
                      skill-effects/set-main-cooldown! (fn [& _] nil)
                      skill-effects/add-skill-exp!     (fn [& _] nil)]
          (cb/apply-invoke perform-fn :player-id "p1" :ctx-id "ctx-1" :exp 0.0)))
      (is (empty? @vel-calls)
          "set-velocity! must NOT be called when can-perform?=false"))))

;; ---------------------------------------------------------------------------
;; 8. :fx :end payload �?reads [:skill-state :performed?], not root context
;; ---------------------------------------------------------------------------

(deftest fx-end-payload-reads-skill-state-test
  (testing ":end payload returns {:performed? true} when [:skill-state :performed?]=true"
    (let [payload-fn (get-in spec [:fx :end :payload])
          test-ctx   {:id "ctx-1" :skill-state {:performed? true}}]
      (with-redefs [ctx/get-context (fn [_id] test-ctx)]
        (is (= {:performed? true}
               (payload-fn {:ctx-id "ctx-1"}))
            "payload reads from [:skill-state :performed?], not root key"))))
  (testing ":end payload returns {:performed? false} when [:skill-state :performed?]=false"
    (let [payload-fn (get-in spec [:fx :end :payload])
          test-ctx   {:id "ctx-1" :skill-state {:performed? false}}]
      (with-redefs [ctx/get-context (fn [_id] test-ctx)]
        (is (= {:performed? false}
               (payload-fn {:ctx-id "ctx-1"})))))))
