(ns cn.li.node.vfx-payload-check-test
  "Compile-time checking of a vfx! spawn payload against the referenced
   effect's declared :inputs.

   Before this, compile-vfx required only a literal :effect-id and passed
   every other field through untouched, so a payload that disagreed with the
   effect compiled clean and failed at spawn time -- a conversion crash for a
   wrong type, or a silently ignored field for a misspelled name. The
   declarations (:type, :map-keys) and the plumbing (:effect-inputs, already
   assembled by the skill catalog) both existed; only the check did not."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.compile :as compile]
            [cn.li.node.types :as types]
            [cn.li.node.surface :as surface]
            [cn.li.node.test-fixtures :as fx]))

;; Mirrors real ac/vfx-v4 content: beam-arc-fade declares exactly these.
(def ^:private effect-inputs
  {:beam-arc-fade {:start {:type :vec3}
                   :end {:type :vec3}
                   :grow-ticks {:type :int}
                   :ring-radius {:type :any :map-keys {:from :double :to :double}}
                   :layers {:type :any}}})

(defn- diagnostics-for [payload]
  (let [doc (surface/read-doc
             (str "{:ability :t :activation :instant :do "
                  "[(vfx! {:effect-id :beam-arc-fade :operation :spawn :payload " (pr-str payload) "})]}"))
        {:keys [diagnostics]} (compile/compile-program
                               (surface/normalize doc)
                               (assoc fx/opts :effect-inputs effect-inputs)
                               :collect)]
    (mapv :message diagnostics)))

(defn- diagnostics-full [payload]
  (let [doc (surface/read-doc
             (str "{:ability :t :activation :instant :do "
                  "[(vfx! {:effect-id :beam-arc-fade :operation :spawn :payload " (pr-str payload) "})]}"))]
    (:diagnostics (compile/compile-program
                   (surface/normalize doc)
                   (assoc fx/opts :effect-inputs effect-inputs)
                   :collect))))

(defn- clean? [payload] (empty? (diagnostics-for payload)))

;; --- the shapes that used to reach runtime -------------------------------

(deftest wrong-scalar-type-into-a-vec3-input-test
  (let [msgs (diagnostics-for {:start 5.0 :end {:vec3 [0.0 1.0 0.0]}})]
    (is (= 1 (count msgs)))
    (is (re-find #":start.*wants :vec3" (first msgs)))))

(deftest scalar-where-the-effect-declares-map-keys-test
  ;; The reported case: an input declared {:from :double :to :double} handed
  ;; a bare double instead.
  (let [msgs (diagnostics-for {:ring-radius 0.34})]
    (is (= 1 (count msgs)))
    (is (re-find #":ring-radius.*:map-keys" (first msgs)))))

(deftest missing-required-map-key-test
  (let [msgs (diagnostics-for {:ring-radius {:from 0.1}})]
    (is (= 1 (count msgs)))
    (is (re-find #"missing required key :to" (first msgs)))))

(deftest wrong-type-inside-a-map-keys-input-test
  (let [msgs (diagnostics-for {:ring-radius {:from 0.1 :to :not-a-number}})]
    (is (= 1 (count msgs)))
    (is (re-find #":ring-radius\.:to.*wants :double" (first msgs)))))

(deftest misspelled-input-name-is-a-warning-test
  ;; Previously silently dropped at runtime -- the effect simply never saw
  ;; it. Reported as a WARNING rather than an error: it is dead weight, not
  ;; a crash, and shipped content still has a few, so making it fatal would
  ;; trade a silent bug for an unbootable game.
  (let [ds (diagnostics-full {:strat {:vec3 [0.0 0.0 0.0]}})]
    (is (= [:unknown-vfx-field] (mapv :code ds)))
    (is (= [:warn] (mapv :severity ds)))
    (is (re-find #"declares no input :strat" (:message (first ds))))))

(deftest a-warning-still-yields-a-usable-ir-test
  (let [doc (surface/read-doc
             (str "{:ability :t :activation :instant :do "
                  "[(vfx! {:effect-id :beam-arc-fade :operation :spawn "
                  ":payload {:strat 1}})]}"))
        {:keys [ir diagnostics]} (compile/compile-program
                                  (surface/normalize doc)
                                  (assoc fx/opts :effect-inputs effect-inputs)
                                  :collect)]
    (is (some? ir) "a warning must not suppress the program")
    (is (= [:warn] (mapv :severity diagnostics))))
  (testing ":throw mode does not throw on a warning either"
    (let [doc (surface/read-doc
               (str "{:ability :t :activation :instant :do "
                    "[(vfx! {:effect-id :beam-arc-fade :operation :spawn "
                    ":payload {:strat 1}})]}"))
          {:keys [ir diagnostics]} (compile/compile-program
                                    (surface/normalize doc)
                                    (assoc fx/opts :effect-inputs effect-inputs)
                                    :throw)]
      (is (some? ir))
      (is (= [:warn] (mapv :severity diagnostics))))))

(deftest unknown-effect-id-test
  (let [doc (surface/read-doc
             "{:ability :t :activation :instant :do
               [(vfx! {:effect-id :no/such-effect :operation :spawn :payload {}})]}")
        {:keys [diagnostics]} (compile/compile-program
                               (surface/normalize doc)
                               (assoc fx/opts :effect-inputs effect-inputs)
                               :collect)]
    (is (= [:unknown-vfx-effect] (mapv :code diagnostics)))))

;; --- what must NOT be reported (the reason plain :type checking was
;; --- originally skipped for payloads at all) -----------------------------

(deftest both-vec3-spellings-are-accepted-test
  (testing "canonical {:vec3 [...]}"
    (is (clean? {:start {:vec3 [1.0 2.0 3.0]}})))
  (testing "bare [x y z], which shipped content also writes"
    (is (clean? {:start [1.0 2.0 3.0]}))))

(deftest an-any-typed-input-accepts-anything-test
  (is (clean? {:layers 3}))
  (is (clean? {:layers [{:n 1}]})))

(deftest omitted-inputs-are-not-reported-test
  ;; An effect graph may default an input; only inputs the payload actually
  ;; supplies are judged.
  (is (clean? {})))

(deftest non-literal-values-are-skipped-test
  ;; A slot wired to a graph node / sigil has no statically known value.
  (is (empty? (types/payload-problems
               :beam-arc-fade (:beam-arc-fade effect-inputs)
               {:start {:ref [:local :eye]}}))))

(deftest checking-is-skipped-without-effect-inputs-test
  ;; vfx-core's own compiles and most unit tests supply none; there is
  ;; nothing to check against and inventing a failure would be worse.
  (let [doc (surface/read-doc
             "{:ability :t :activation :instant :do
               [(vfx! {:effect-id :beam-arc-fade :operation :spawn :payload {:start 5.0}})]}")
        {:keys [diagnostics]} (compile/compile-program (surface/normalize doc) fx/opts :collect)]
    (is (empty? diagnostics))))

;; --- mode plumbing --------------------------------------------------------

(deftest throw-mode-refuses-to-produce-ir-test
  ;; This is what makes the skill catalog fail at startup instead of at spawn.
  (let [doc (surface/read-doc
             "{:ability :t :activation :instant :do
               [(vfx! {:effect-id :beam-arc-fade :operation :spawn :payload {:start 5.0}})]}")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"wants :vec3"
         (compile/compile-program (surface/normalize doc)
                                  (assoc fx/opts :effect-inputs effect-inputs)
                                  :throw)))))

;; --- :operation, against the VFX signal ABI -------------------------------
;; A typo'd operation used to compile clean and then throw "unknown VFX
;; signal operation" out of vfx-contract/signal at spawn time.

(def ^:private signal-ops
  ;; Mirrors cn.li.mcmod.runtime.vfx-contract/signal-ops, which node-core
  ;; must not depend on -- the real set is passed in by the caller.
  #{:spawn :update :trigger :destroy :release :clear-owner :snapshot})

(defn- operation-diagnostics [op & {:keys [ops] :or {ops signal-ops}}]
  (let [doc (surface/read-doc
             (str "{:ability :t :activation :instant :do "
                  "[(vfx! {:effect-id :beam-arc-fade :operation " op
                  " :instance-key [:a :b]})]}"))]
    (:diagnostics (compile/compile-program
                   (surface/normalize doc)
                   (cond-> (assoc fx/opts :effect-inputs effect-inputs)
                     ops (assoc :vfx-operations ops))
                   :collect))))

(deftest unknown-vfx-operation-test
  (let [ds (operation-diagnostics ":bogus")]
    (is (= [:invalid-vfx-operation] (mapv :code ds)))
    (is (re-find #":bogus is not one of" (:message (first ds))))))

(deftest every-abi-operation-is-accepted-test
  (doseq [op signal-ops]
    (is (empty? (operation-diagnostics (str op)))
        (str op " is a real VFX signal op and must not be reported"))))

(deftest operation-checking-is-skipped-without-the-abi-set-test
  ;; Same discipline as :effect-inputs: nil means "no ABI supplied", not
  ;; "no operation is valid".
  (is (empty? (operation-diagnostics ":bogus" :ops nil))))

(deftest stop-operation-carries-no-payload-test
  (let [doc (surface/read-doc
             "{:ability :t :activation :instant :do
               [(vfx! {:effect-id :beam-arc-fade :operation :stop :instance-key [:a :b]})]}")
        {:keys [diagnostics]} (compile/compile-program
                               (surface/normalize doc)
                               (assoc fx/opts :effect-inputs effect-inputs)
                               :collect)]
    (is (empty? diagnostics))))
