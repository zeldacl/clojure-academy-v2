(ns cn.li.ability.type-contract-baseline-test
  "Ratchet on how much of the node-language's type surface is untyped.

   The recurring V4 authoring bugs -- a node call missing an argument, a map
   passed where a list is wanted, nil reaching a :double -- are not escaping
   because the checks are wrong. cn.li.node.compile reports ~30 diagnostic
   codes. (An earlier version of this note also credited cn.li.node.graph-
   document's ~60 structural invariants; those were about wires and went
   with the node/wire form -- a surface document cannot have a dangling
   link.)

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
      ;; 58 -> 57 when :combat/damage's :damage-pipeline was removed: it was
      ;; declared, set by five skills, and read by nothing in the host or
      ;; upstream.
      ;; 57 -> 54 when :kernel/terrain-wave-plan's spread / energy-cost /
      ;; block-transforms were tagged. That node takes seventeen positional
      ;; params, nine of them :double, so the mistake these three now catch
      ;; is an argument landing in the wrong slot.
      ;; 54 -> 49 when the raycast family stopped sharing one capability:
      ;; four :policy params could finally name a shape (one per handler),
      ;; and the fifth was deleted outright because its callee never read
      ;; it.
      (is (= 49 (count anys))
          (str "combat vocab :any-typed params changed. Offenders:\n"
               (str/join "\n" (map #(str "  " (:node %) " / " (:param %))
                                   (sort-by (juxt :node :param) anys))))))
    (testing "optional parameters are why a missing wire cannot raise :missing-param"
      ;; 115 -> 118: :world/sound's :source/:volume/:pitch. sound! read all
      ;; three off the request already; the node declaring none of them was
      ;; why content could not set them.
      (is (= 118 (count (filter :optional? rows)))
          "combat vocab optional param count changed")
      ;; 231 -> 236: :target/penetration is new (+6) and
      ;; :target/directional-destination-query lost its unread :policy (-1).
      ;; 236 -> 239: :world/sound's three (see above).
      (is (= 239 (count rows))
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
  ;; Named exemptions, same discipline as the :returns side below, in two
  ;; groups with genuinely different stakes.
  ;;
  ;; The :boolean ones are the mild case -- a missing key silently becomes
  ;; false rather than throwing. Each producer sets them explicitly anyway:
  ;; (some? hit), (not miss?), (= :entity (:hit-type result)),
  ;; (boolean (and ...)), so the nil -> false conversion cannot fire.
  ;;
  ;; :resource-pool is the sharp case: a :doubles register write THROWS on
  ;; nil, so exempting it is a claim that the value is never nil, not
  ;; merely that it is usually set. The claim holds because the host
  ;; materializes the pool in one place and applies the zero default at the
  ;; producer. If that ever stops being true this exemption becomes wrong
  ;; and nothing else will catch it, so it is listed here with the reason
  ;; rather than left to be inferred from the table.
  ;;
  ;; It is also the one entry in the UNIFORM form -- a bare type rather
  ;; than a {key type} map, meaning every field of that record has this
  ;; type. It is spelled that way because the neutral engine is not allowed
  ;; to name the content module's resources; the key set is pinned by that
  ;; module's own test instead. The :* below is this test's spelling for
  ;; "whatever key is read", not a real field name.
  ;;
  ;; Declaring any of them :any to dodge the rule would lose a real type
  ;; for no safety gained; listing them records that someone checked the
  ;; producer.
  (let [allowed-primitive #{[:destination :hit?] [:destination :valid?]
                            [:hit-result :attacked?] [:hit-result :water?]
                            ;; (boolean on-ground?) in owner-snapshot!
                            [:owner-snapshot :on-ground?]
                            ;; (boolean snapshot) / (boolean (and ...)) in
                            ;; item-held! -- both wrap, so neither is nil.
                            ;; :can-fly? is NOT here: it is a bare `and` and
                            ;; can be nil, so it stays :any.
                            [:item-snapshot :present?] [:item-snapshot :placeable?]
                            ;; (boolean (and tile ...)) in
                            ;; combat-runtime/energy-target-result.
                            [:energy-target :chargeable?]
                            ;; the host applies (double (or ... 0.0)) when
                            ;; it materializes the pool.
                            [:resource-pool :*]
                            ;; (double (or (:hardness block) 0.0)) and two
                            ;; (boolean (and ...)) in block-select!, which
                            ;; builds every member in one mapv.
                            [:block-info :hardness]
                            [:block-info :breakable?]
                            [:block-info :requires-high-tier-tool?]
                            ;; project-entity, all (double (or ... d)) /
                            ;; (long (or ... n)) / (boolean ...). Its three
                            ;; BARE passthroughs -- :explosion-power,
                            ;; :velocity, :owner-id -- are deliberately not
                            ;; typed at all rather than listed here, since
                            ;; nil is their normal value.
                            [:entity-ref :width] [:entity-ref :height]
                            [:entity-ref :eye-height] [:entity-ref :age-ms]
                            [:entity-ref :motion-progress]
                            [:entity-ref :difficulty]
                            [:entity-ref :invulnerable-time]
                            [:entity-ref :item?] [:entity-ref :projectile?]
                            [:entity-ref :arrow?]
                            ;; beam-trace!: :damage/:eye-height/:living?
                            ;; are always set. The two :reflection-* here
                            ;; are the deliberate exception to this list's
                            ;; usual rule -- their key CAN be absent, and
                            ;; the primitive bank is chosen so that reading
                            ;; one outside its :reflection-accepted? guard
                            ;; fails at the read rather than turning into a
                            ;; quiet 0.0. See the schema's own comment.
                            [:beam-hit :damage] [:beam-hit :eye-height]
                            [:beam-hit :living?]
                            [:beam-hit :reflection-accepted?]
                            [:beam-hit :reflection-damage]
                            ;; Both computed rather than reported: one
                            ;; unconditional (Math/sqrt ...) each, in
                            ;; resolve-destination and in raycast!'s
                            ;; normalizer. Neither reads a bridge's own
                            ;; :distance, so there is no branch on which
                            ;; the key can be missing -- including the
                            ;; miss, whose distance is the full ray length.
                            [:destination :distance] [:hit-result :distance]
                            ;; march-through-collision builds its result in
                            ;; two places and both set all five keys;
                            ;; penetration-raycast's two assocs on top are
                            ;; unconditional. So no branch omits any of
                            ;; these -- when the march returns at all, it
                            ;; returns them.
                            [:penetration-result :distance]
                            [:penetration-result :march-distance]
                            [:penetration-result :available?]
                            [:penetration-result :valid?]
                            [:penetration-result :hit?]}
        declared (for [[type-tag schema] combat-vocab/field-types
                       [field-key field-type] (if (map? schema)
                                                schema
                                                {:* schema})]
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
    (is (= #{:random/chance :random/int :random/uniform :cost/spend
             ;; (boolean (when-let [stack ...] ...)) in combat-runtime, so
             ;; never nil -- and it is a :boolean precisely to avoid needing
             ;; a record and a field schema for one flag.
             :energy/held-item-supported?}
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
    ;; 8 -> 7: :terrain/apply-break-budget's `blocks` is now
    ;; [:list-of :block-info]. It had been counted with the others as
    ;; "structured values the lattice has no name for", but that was only
    ;; half true -- the lattice could say it, surface/normalize could not
    ;; READ it, because the :defn param grammar demanded a keyword and so
    ;; excluded every structural type. Worth separating the two reasons: a
    ;; missing type is work, a grammar that cannot express an existing type
    ;; is a bug, and this row had been filed under the wrong one.
    ;;
    ;; 7 -> 4: :terrain/wave-plan's spread / energy-cost / block-transforms
    ;; are now tagged. They take a tag and NO field schema on purpose --
    ;; content builds all three and never field-reads them, and the two
    ;; block-id-keyed tables treat a missing key as "not listed" rather
    ;; than an error, which a uniform schema would bank primitively and
    ;; turn into a silent 0.0.
    ;;
    ;; 4 -> 0. The four were all a `policy` map, and they could only be
    ;; typed once the raycast family stopped sharing a single :raycast
    ;; capability behind a :query-kind nothing set -- one handler serving
    ;; five request shapes is why :policy had no shape to name. Three now
    ;; carry the tag of the handler that reads them and the fourth
    ;; (:target/directional-destination's) is gone, because
    ;; targeting/directional-destination never destructured a :policy.
    ;;
    ;; ZERO is now an invariant, not a ratchet: a new :any-typed :defn
    ;; param means someone added an untyped one.
    (is (= 0 (count (any-typed rows)))
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
