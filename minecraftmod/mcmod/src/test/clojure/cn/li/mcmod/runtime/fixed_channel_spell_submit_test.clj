(ns cn.li.mcmod.runtime.fixed-channel-spell-submit-test
  "S7: cn.li.mcmod.runtime.fixed-channel's new player-spell-submit packet
   round-trips real glyph data and enforces its own bounds -- the wire
   half of cn.li.combat.player's server-authoritative desugar/admit path."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.fixed-channel :as fixed-channel]))

(def ^:private sample-glyphs
  [{:glyph :form/touch :params {:range 16.0}}
   {:glyph :effect/damage :params {:amount 4.0}}
   {:glyph :augment/amplify}])

(deftest player-spell-submit-round-trips-test
  (let [packet (fixed-channel/encode-player-spell-submit sample-glyphs)]
    (is (bytes? packet))
    (is (= sample-glyphs (fixed-channel/decode-player-spell-submit packet)))))

(deftest player-spell-submit-rejects-a-non-vector-test
  (is (thrown? clojure.lang.ExceptionInfo
               (fixed-channel/encode-player-spell-submit {:glyph :form/self}))))

(deftest player-spell-submit-rejects-oversize-payloads-test
  (let [huge (vec (repeat 400 {:glyph :augment/amplify :params {:padding (apply str (repeat 64 \x))}}))]
    (is (thrown? clojure.lang.ExceptionInfo (fixed-channel/encode-player-spell-submit huge)))))

(deftest player-spell-submit-decode-rejects-wrong-packet-type-test
  (let [combat-feedback-packet (fixed-channel/encode-combat-feedback {:status :ok :feedback []})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (fixed-channel/decode-player-spell-submit combat-feedback-packet)))))
