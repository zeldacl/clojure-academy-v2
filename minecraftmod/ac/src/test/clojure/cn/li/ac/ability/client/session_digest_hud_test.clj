(ns cn.li.ac.ability.client.session-digest-hud-test
  "The HUD features that read active skill sessions.

   Every assertion here was RED before the session digest existed. The v4
   migration (5be38fe57) deleted the legacy Context runtime, whose
   context_dispatcher was the only issuer of the :register-context command
   that populated player-state's :context-registry. reactive_hud kept
   reading that registry through read-model, so from that commit onward it
   read {} forever and four HUD features rendered their inert branch with
   nothing to indicate anything was wrong:

     - body-intensify's charge bar (:active? false, ratio 0.0)
     - railgun's coin-QTE charge readout
     - the red CP consumption-hint line
     - vec-reflection / vec-deviation's screen visuals

   The lesson is the shape of these tests: asserting a HUD function returns
   a map proves nothing, because the dead branch returns one too. Each test
   below installs a digest and asserts the LIVE value differs from the
   inert one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.client.reactive-hud :as reactive-hud]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.runtime.fixed-channel :as fixed-channel]))

(def ^:private player "digest-player")

(use-fixtures :each
  (fn [f]
    (read-model/clear-all-session-digests!)
    (try
      ;; coin-qte-visual-state's non-:item-charge arm resolves an owner-key,
      ;; which needs a client session id.
      (runtime-hooks/with-client-ctx-fn {:session-id :digest-test} f)
      (finally (read-model/clear-all-session-digests!)))))

(deftest digest-round-trips-over-the-wire-test
  (testing "the four fields the HUD renders survive encode/decode"
    (let [sessions [{:skill-id :railgun :mode :item-charge
                     :hold-ticks 12 :elapsed-ticks 30 :exp 0.75}]
          decoded (fixed-channel/decode-session-state
                   (fixed-channel/encode-session-state {:sessions sessions}))]
      (is (= :session-state (:type decoded)))
      (is (= sessions (:sessions decoded)))))

  (testing "a program's unbounded state never reaches the wire"
    ;; vec-reflection's :visited-projectiles grows with every projectile it
    ;; touches. The projection names its fields rather than shipping state,
    ;; so an extra key is dropped instead of being encoded.
    (let [decoded (fixed-channel/decode-session-state
                   (fixed-channel/encode-session-state
                    {:sessions [{:skill-id :vec-reflection
                                 :visited-projectiles (vec (range 500))}]}))]
      (is (= [:skill-id :mode :hold-ticks :elapsed-ticks :exp]
             (keys (first (:sessions decoded)))))
      (is (nil? (:visited-projectiles (first (:sessions decoded)))))))

  (testing "a digest past the bound is rejected rather than allocated"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fixed-channel/encode-session-state
                  {:sessions (vec (repeat (inc fixed-channel/max-session-digest-entries)
                                          {:skill-id :railgun}))})))))

(deftest absent-digest-reads-empty-test
  (is (= [] (read-model/get-player-contexts-for-player player)))
  (testing "an empty push clears rather than storing an empty vector -- this
            is the packet that ends a charge bar when the session ends"
    (read-model/apply-session-digest! player [{:skill-id :railgun}])
    (is (seq (read-model/get-player-contexts-for-player player)))
    (read-model/apply-session-digest! player [])
    (is (= [] (read-model/get-player-contexts-for-player player)))))

(deftest charge-readouts-track-the-digest-test
  (testing "body-intensify's charge bar follows :hold-ticks"
    ;; Inert branch: {:active? false :charge-ticks 0 :charge-ratio 0.0}.
    (let [inert (reactive-hud/visual-state :ac/body-intensify-charge
                                           {:player-uuid player})]
      (is (false? (:active? inert)))
      (is (zero? (long (:charge-ticks inert)))))
    (read-model/apply-session-digest!
     player [{:skill-id :body-intensify :hold-ticks 7}])
    (let [live (reactive-hud/visual-state :ac/body-intensify-charge
                                          {:player-uuid player})]
      (is (true? (:active? live)))
      (is (= 7 (long (:charge-ticks live))))
      (is (pos? (double (:charge-ratio live))))))

  (testing "railgun's coin QTE reads the :item-charge arm"
    (read-model/clear-session-digest! player)
    (is (false? (:active? (reactive-hud/visual-state
                           :ac/charge-coin {:player-uuid player :now-ms 0}))))
    ;; :mode is what selects this arm -- a session in any other mode must
    ;; NOT light it up, or the readout would show on every railgun cast.
    (read-model/apply-session-digest!
     player [{:skill-id :railgun :mode :armed :hold-ticks 9}])
    (is (false? (:active? (reactive-hud/visual-state
                           :ac/charge-coin {:player-uuid player :now-ms 0}))))
    (read-model/apply-session-digest!
     player [{:skill-id :railgun :mode :item-charge :hold-ticks 9}])
    (let [live (reactive-hud/visual-state :ac/charge-coin
                                          {:player-uuid player :now-ms 0})]
      (is (true? (:active? live)))
      (is (= 9 (long (:charge-ticks live)))))))

(deftest delegate-state-distinguishes-charge-from-active-test
  (testing "a running session glows, and a charging one glows differently"
    (let [charging (dstate/delegate-state-for-slot
                    [{:skill-id :railgun :hold-ticks 5}] :railgun)
          active (dstate/delegate-state-for-slot
                  [{:skill-id :railgun :hold-ticks 0}] :railgun)
          idle (dstate/delegate-state-for-slot [] :railgun)]
      (is (= :charge (:state charging)))
      (is (= :active (:state active)))
      (is (= :idle (:state idle)))
      ;; The three must be visually distinct, or deriving them was pointless.
      (is (= 3 (count (distinct [(:glow-color charging)
                                 (:glow-color active)
                                 (:glow-color idle)])))))))
