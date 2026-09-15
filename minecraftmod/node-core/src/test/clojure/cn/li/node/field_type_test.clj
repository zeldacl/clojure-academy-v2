(ns cn.li.node.field-type-test
  "Field reads carry a type when the source's type has a schema.

   :value/field is the second most common component in shipped V4 content
   (380 uses across skills-v4, :position alone read 122 times), and every
   one of them used to produce an :any register. That is why so little of a
   graph was actually checked: assignable?'s `from :any` rule waves an :any
   through into any parameter, and cn.li.node.static-check only reasons
   about compile-time-resolvable values, which a host query result never is.
   So the value came back untyped and stayed untyped all the way to its use
   site."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.compile :as compile]
            [cn.li.node.surface :as surface]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private field-types
  {:hit-result {:position :vec3 :hit-type :keyword}})

(defn- compile-do
  "Compile a :do body with the fixture vocab plus `field-types`, collecting."
  [body opts-extra]
  (compile/compile-program
   (surface/normalize
    (surface/read-doc (str "{:ability :t :activation :instant :do " body "}")))
   (merge fx/opts opts-extra)
   :collect))

(deftest a-field-read-is-typed-from-the-sources-schema-test
  ;; target/raycast returns :hit-result, so (:position hit) is a :vec3 and
  ;; may be passed where a :vec3 is wanted. Without a schema it is :any,
  ;; which ALSO compiles -- so the positive case cannot distinguish the two
  ;; on its own. That is what the negative test below is for.
  (let [{:keys [ir diagnostics]}
        (compile-do (str "[(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim"
                         " :distance 10.0}))"
                         " (let n (vec3/length (:position hit)))"
                         " (finish {:outcome :performed})]")
                    {:field-types field-types})]
    (is (= [] (vec diagnostics)))
    (is (some? ir))))

(deftest a-typed-field-read-is-rejected-where-the-type-does-not-fit-test
  ;; The half that proves the type is real rather than decorative:
  ;; (:hit-type hit) is a :keyword, and vec3/length wants a :vec3.
  (let [{:keys [ir diagnostics]}
        (compile-do (str "[(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim"
                         " :distance 10.0}))"
                         " (let n (vec3/length (:hit-type hit)))"
                         " (finish {:outcome :performed})]")
                    {:field-types field-types})]
    (is (= [:type-mismatch] (mapv :code diagnostics)))
    (is (nil? ir)))
  (testing "and WITHOUT the schema the very same graph compiles clean"
    ;; The before/after in one assertion: this is exactly the class of bug
    ;; that reached runtime, and the only thing that changed is whether the
    ;; source type had a field schema.
    (let [{:keys [ir diagnostics]}
          (compile-do (str "[(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim"
                           " :distance 10.0}))"
                           " (let n (vec3/length (:hit-type hit)))"
                           " (finish {:outcome :performed})]")
                      {})]
      (is (= [] (vec diagnostics)))
      (is (some? ir)))))

(deftest an-unlisted-field-stays-any-rather-than-erroring-test
  ;; Schemas are OPEN. Two records share the :destination tag today with
  ;; different key sets (targeting/directional-destination's six vs
  ;; platform resolve-destination's thirteen), so no key set is truthfully
  ;; complete and rejecting an unlisted field would reject legal reads.
  ;; Splitting those tags per producer is the prerequisite for closing
  ;; schemas -- and for catching a typo'd field name at all.
  (let [{:keys [ir diagnostics]}
        (compile-do (str "[(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim"
                         " :distance 10.0}))"
                         " (let e (:no-such-field hit))"
                         " (finish {:outcome :performed})]")
                    {:field-types field-types})]
    (is (= [] (vec diagnostics)) "an unlisted field is not an error yet")
    (is (some? ir))))

(deftest a-field-register-never-lands-in-a-primitive-bank-test
  ;; Guardrail for the deferred one-way-:any round, mirroring
  ;; returning-a-primitive-is-an-explicit-decision-test on the :returns
  ;; side. compile banks a field register by its declared type, so a
  ;; :double field would move it into a primitive array and
  ;; effect-emit's compile-writer would throw :nil-primitive-write the
  ;; first time the host returned nil for it. Numeric fields are therefore
  ;; declared :any in the combat-core table for now; this pins the
  ;; consequence rather than the convention.
  (let [{:keys [ir]}
        (compile-do (str "[(let hit (target/raycast {:from ?caster/eye :dir ?caster/aim"
                         " :distance 10.0}))"
                         " (let p (:position hit))"
                         " (finish {:outcome :performed})]")
                    {:field-types field-types})
        get-instrs (for [b (:blocks ir) i (:instrs b) :when (= :get (:op i))] i)]
    (is (seq get-instrs) "the fixture must actually produce a :get")
    (is (every? #(= :objects (second (:dst %))) get-instrs)
        "every field read must allocate in the :objects bank")))
