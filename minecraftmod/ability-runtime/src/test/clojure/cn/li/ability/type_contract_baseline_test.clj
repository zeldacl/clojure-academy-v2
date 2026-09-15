(ns cn.li.ability.type-contract-baseline-test
  "Ratchet on how much of the node-language's type surface is untyped.

   The recurring V4 authoring bugs -- a node call missing an argument, a map
   passed where a list is wanted, nil reaching a :double -- are not escaping
   because the checks are wrong. cn.li.node.compile reports ~30 diagnostic
   codes and cn.li.node.graph-document enforces ~60 structural invariants.
   They escape because the CONTRACT those checks read is nearly vacuous:
   cn.li.node.types/assignable? has exactly four rules and two of them are
   :any passing in either direction, so every :any-typed declaration is a
   hole the checker is obliged to accept.

   Worse, :any actively DISABLES checks that already exist:
   cn.li.node.types' :missing-vfx-input and :nil-vfx-input both skip an
   input whose declared type is :any.

   So this namespace asserts the EXACT count of untyped declarations in
   every source of truth the compiler consults. Exact, not an upper bound:
   an upper bound silently absorbs progress, and these numbers are supposed
   to be driven down phase by phase (each drop is a deliberate edit that
   should update the number here in the same commit). A number going UP is
   a new hole; a number going DOWN without this file changing is
   impossible, which is the point.

   Lives in ability-runtime, not node-core: node-core has zero project
   dependencies on purpose (verifyNodeCoreDependencyDirection forbids
   adding any), and this has to read combat-core's and vfx-core's
   vocabularies alongside node-core's op table. ability-runtime is the
   lowest module that `api`-depends on all three."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cn.li.combat.dsl-vocabulary :as combat-vocab]
            [cn.li.combat.lib :as combat-lib]
            [cn.li.node.ops :as ops]
            [cn.li.node.types :as types]
            [cn.li.vfx.dsl-vocabulary :as vfx-vocab]))

;; --- shared extraction ------------------------------------------------------
;;
;; A vocab node's :params is {param-key {:type t :default d?}}. The `opt`
;; helper in dsl_vocabulary always sets :default (even to nil), while `p`
;; and `p*` never do -- so (contains? spec :default) is the reliable
;; required/optional test. `(nil? (:default spec))` is NOT: (opt :any nil)
;; is optional with a nil default.

(defn- param-rows
  "vocab nodes map -> [{:node :param :type :optional?}], one per declared param."
  [nodes]
  (for [[node-id spec] nodes
        [param-key pspec] (:params spec)]
    {:node node-id
     :param param-key
     :type (:type pspec)
     :optional? (contains? pspec :default)}))

(defn- any-typed
  [rows]
  (filter #(= :any (types/canonical-type (:type %))) rows))

;; --- combat vocabulary ------------------------------------------------------

(deftest combat-vocabulary-untyped-parameter-count-test
  (let [rows (param-rows combat-vocab/nodes)
        anys (any-typed rows)]
    (testing "every :any parameter is a declaration assignable? is forced to accept"
      (is (= 58 (count anys))
          (str "combat vocab :any-typed params changed. Offenders:\n"
               (str/join "\n" (map #(str "  " (:node %) " / " (:param %))
                                   (sort-by (juxt :node :param) anys))))))
    (testing "optional parameters are why a missing wire cannot raise :missing-param"
      (is (= 116 (count (filter :optional? rows)))
          "combat vocab optional param count changed")
      (is (= 232 (count rows))
          "combat vocab total param count changed"))))

(deftest combat-vocabulary-return-type-count-test
  (let [returns (map :returns (vals combat-vocab/nodes))
        value-returning (remove nil? returns)
        untyped (filter #(= :any (types/canonical-type %)) value-returning)]
    (testing "an :any return is the root of :value/field having nothing to infer from"
      ;; Down from 14. The one left is :data/random-item, which returns an
      ;; element of its own :items argument -- parametric, and node-core's
      ;; signature is monomorphic. See its entry in dsl_vocabulary.
      (is (= 1 (count untyped))
          (str "combat vocab :returns :any count changed. Offenders: "
               (pr-str (sort (keep (fn [[k v]] (when (= :any v) k))
                                   (map (juxt key (comp :returns val)) combat-vocab/nodes)))))))
    (is (= 32 (count (filter nil? returns)))
        "combat vocab action (nil :returns) count changed")
    (testing "every declared return is a type the checker actually knows"
      (is (= [] (vec (remove types/known-type? value-returning)))
          "a :returns naming something outside the type lattice checks nothing"))))

(deftest field-schemas-never-put-a-read-in-a-primitive-bank-test
  ;; The :value/field half of the guardrail below. compile banks a field
  ;; register by its declared type, so a field declared :double/:long moves
  ;; that register into a primitive array where effect-emit's
  ;; compile-writer throws :nil-primitive-write the first time the host
  ;; returns nil for it. Numeric fields are declared :any for now and the
  ;; table says so field by field; this makes that a build failure rather
  ;; than a convention someone has to notice.
  ;; Named exemptions, same discipline as the :returns side below. These
  ;; four are genuinely :boolean and their producers always set them
  ;; explicitly -- (some? hit), (not miss?), (= :entity (:hit-type result)),
  ;; (boolean (and ...)) -- so the key is never absent and the nil -> false
  ;; conversion cannot fire. Declaring them :any to dodge the rule would
  ;; lose a real type for no safety gained; listing them records that
  ;; someone checked the producer.
  (let [allowed-primitive #{[:destination :hit?] [:destination :valid?]
                            [:hit-result :attacked?] [:hit-result :water?]
                            ;; (boolean on-ground?) in owner-snapshot!
                            [:owner-snapshot :on-ground?]
                            ;; (boolean snapshot) / (boolean (and ...)) in
                            ;; item-held! -- both wrap, so neither is nil.
                            ;; :can-fly? is NOT here: it is a bare `and` and
                            ;; can be nil, so it stays :any.
                            [:item-snapshot :present?] [:item-snapshot :placeable?]}
        declared (for [[type-tag fields] combat-vocab/field-types
                       [field-key field-type] fields]
                   {:type type-tag :field field-key :field-type field-type})
        offenders (remove #(or (= :objects (types/bank (:field-type %)))
                               (contains? allowed-primitive [(:type %) (:field %)]))
                          declared)]
    (is (seq declared) "the field-type table must not be empty -- see T3")
    (is (= [] (vec offenders))
        (str "a field schema moved a read into a primitive bank. Confirm the"
             " producer can never omit the key, then list it above: "
             (pr-str (vec offenders))))
    (testing "and every declared field type is one the checker knows"
      (is (= [] (vec (remove #(types/known-type? (:field-type %)) declared)))))
    (testing "schemas only describe types the lattice actually has"
      (is (= [] (vec (remove types/known-type? (keys combat-vocab/field-types))))))))

(deftest returning-a-primitive-is-an-explicit-decision-test
  ;; The guardrail for the deferred one-way-:any work, and the reason the
  ;; return-typing pass above used only :objects-bank types.
  ;;
  ;; cn.li.node.compile allocates a query's destination register with
  ;; (types/bank returns). :any banks to :objects, so today's results are
  ;; boxed and a nil is harmless. Retyping a return to :double or :long
  ;; moves that register into a primitive bank, where effect-emit's
  ;; compile-writer throws :nil-primitive-write the first time the host
  ;; hands back nil -- and :boolean is worse, because nil quietly becomes
  ;; false instead of throwing. Either is a runtime behaviour change
  ;; smuggled in under what looks like a declaration-only edit.
  ;;
  ;; So: primitive returns are allowed, but only by name, here.
  (let [primitive-returns (set (keep (fn [[node-id spec]]
                                       (when (and (:returns spec)
                                                  (not= :objects (types/bank (:returns spec))))
                                         node-id))
                                     combat-vocab/nodes))]
    (is (= #{:random/chance :random/int :random/uniform :cost/spend}
           primitive-returns)
        (str "a vocab node's :returns moved into a primitive register bank. "
             "Confirm the host can never return nil for it, then list it here: "
             (pr-str (sort primitive-returns))))))

;; --- vfx vocabulary ---------------------------------------------------------

(deftest vfx-vocabulary-untyped-parameter-count-test
  (let [rows (param-rows vfx-vocab/nodes)]
    (is (= 35 (count (any-typed rows)))
        "vfx vocab :any-typed param count changed")
    (is (= 60 (count (filter :optional? rows)))
        "vfx vocab optional param count changed")
    (is (= 132 (count rows))
        "vfx vocab total param count changed")
    (testing "vfx nodes are all scene actions -- nothing here returns a value"
      (is (every? nil? (map :returns (vals vfx-vocab/nodes)))
          "a vfx vocab node gained a :returns; P2's return-typing work now applies here too"))))

;; --- node-core pure op table ------------------------------------------------

(deftest pure-op-table-untyped-count-test
  (let [rows (for [[op {:keys [params returns]}] ops/table]
               {:op op
                :any-params (count (filter #(= :any (types/canonical-type %)) params))
                :any-return? (= :any (types/canonical-type returns))})]
    (testing ":any in a pure op's signature is the same hole as in the vocabulary"
      (is (= 15 (reduce + (map :any-params rows)))
          "pure op :any-typed parameter count changed")
      (is (= 4 (count (filter :any-return? rows)))
          "pure op :any return count changed"))
    (testing "an :any param whose op returns a primitive is a latent nil crash"
      ;; :pair/first is the archetype: {:params [:any] :returns :double}. The
      ;; result register is allocated in the :doubles bank (cn.li.node.
      ;; compile's alloc-reg! via types/bank), so cn.li.mcmod.runtime.
      ;; effect-emit's compile-writer throws :nil-primitive-write the moment
      ;; the :any argument turns out to be nil at runtime. Counted, not
      ;; fixed here -- fixing it is the deferred gap C round.
      ;; The 3 today are :pair/first, :pair/second and :pair/third.
      (is (= 3 (count (filter #(and (pos? (:any-params %))
                                    (contains? #{:doubles :longs}
                                               (types/bank (:returns (get ops/table (:op %))))))
                              rows)))
          "count of :any-in/primitive-out pure ops changed"))))

;; --- combat :defn library ---------------------------------------------------

(deftest defn-library-untyped-count-test
  (let [rows (for [[fn-id doc] combat-lib/fns
                   p (:params doc)]
               {:fn fn-id :param (:name p) :type (:type p)})]
    ;; Today's 8 are all genuinely structured values the type lattice has no
    ;; name for yet -- a `policy` map (:target/hold-destination,
    ;; :target/raycast-destination, :target/directional-destination,
    ;; :combat/beam-strike's reflection-policy), a block list
    ;; (:terrain/apply-break-budget), and :terrain/wave-plan's spread /
    ;; energy-cost / block-transforms. They are T2/T5 material: each needs
    ;; either an opaque tag or a :map-keys schema before it can stop being
    ;; :any, which is exactly the work this ratchet is here to track.
    (is (= 8 (count (any-typed rows)))
        (str ":defn library :any-typed param count changed. Offenders: "
             (pr-str (map (juxt :fn :param) (any-typed rows)))))
    (is (= 0 (count (filter #(= :any (types/canonical-type (:returns %)))
                            (vals combat-lib/fns))))
        ":defn library :any return count changed")))

;; --- the one invariant that must never regress ------------------------------

(deftest assignable-is-the-only-type-judgement-test
  (testing "both :any directions are still open -- this is the contract gap itself"
    ;; Asserted, not assumed: the whole plan is built on assignable? being
    ;; this permissive, and gap C (making `from :any` one-way) is a LATER
    ;; round. If someone tightens it early, every count above shifts and
    ;; the phase ordering needs revisiting -- better to fail here loudly.
    (is (true? (types/assignable? :any :double))
        "assignable? no longer lets an unknown flow into :double -- gap C landed early")
    (is (true? (types/assignable? :double :any))
        "assignable? no longer lets a concrete type flow into :any"))
  (testing ":long widens to :double but :double does not narrow to :long"
    (is (true? (types/assignable? :long :double)))
    (is (false? (types/assignable? :double :long))))
  (testing "there is still no structural rule for lists"
    ;; P5 depends on this: [:list-of t] can only match by value equality
    ;; today, which is why P5 reaches for the existing :entity-list/
    ;; :block-list opaque tags instead of introducing [:list-of :any].
    (is (false? (types/assignable? [:list-of :entity-ref] [:list-of :any]))
        "assignable? gained a list-of rule -- P5's zero-change path may no longer be the right one")))
