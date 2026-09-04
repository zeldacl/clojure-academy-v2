(ns cn.li.node.nid-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.nid :as nid]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.test-fixtures :as fx]))

(def ^:private text
  "{:ability :t
    :do [(cooldown/start {:name :main :ticks 40})
         (finish {:outcome :performed})]}")

(deftest stamp-assigns-a-distinct-nid-to-each-statement-test
  (let [doc (nid/stamp (surface/read-doc text))
        [s1 s2] (:do doc)
        n1 (:nid (meta s1)) n2 (:nid (meta s2))]
    (is (some? n1))
    (is (some? n2))
    (is (not= n1 n2))))

(deftest stamp-is-idempotent-test
  (let [doc (nid/stamp (surface/read-doc text))
        doc2 (nid/stamp doc)]
    (is (= (mapv (comp :nid meta) (:do doc))
           (mapv (comp :nid meta) (:do doc2))))))

(deftest inserting-a-statement-does-not-shift-existing-nids-test
  (let [doc (nid/stamp (surface/read-doc text))
        [s1 s2] (:do doc)
        n1 (:nid (meta s1)) n2 (:nid (meta s2))
        inserted-form (first (:do (surface/read-doc "{:ability :t :do [(cost/spend {:budget {}})]}")))
        doc-with-insert (assoc doc :do (into [inserted-form] (:do doc)))
        restamped (nid/stamp doc-with-insert)
        [n0-form n1-form n2-form] (:do restamped)]
    (is (= n1 (:nid (meta n1-form))) "existing statement's nid must not shift")
    (is (= n2 (:nid (meta n2-form))) "existing statement's nid must not shift")
    (is (some? (:nid (meta n0-form))))
    (is (not (contains? #{n1 n2} (:nid (meta n0-form)))) "the new statement must not collide with an existing nid")))

(deftest collect-finds-every-stamped-nid-test
  (let [doc (nid/stamp (surface/read-doc text))
        found (nid/collect doc)]
    (is (>= (count found) 2))
    (is (every? #(re-matches #"n\d+" %) found))))

(deftest re-stamping-a-partial-doc-does-not-collide-with-hand-written-nids-test
  ;; A hand-authored ^{:nid "n7"} on one statement, everything else unstamped:
  ;; stamp must seed its counter above 7, never emitting a second "n7".
  (let [text "{:ability :t
               :do [^{:nid \"n7\"} (cooldown/start {:name :main :ticks 40})
                    (finish {:outcome :performed})]}"
        doc (nid/stamp (surface/read-doc text))
        [s1 s2] (:do doc)]
    (is (= "n7" (:nid (meta s1))))
    (is (not= "n7" (:nid (meta s2))))))

(deftest compile-reads-stamps-nid-directly-off-in-memory-metadata-test
  ;; Integration: a stamped raw-doc's nids show up verbatim on the
  ;; resulting IR instructions -- nid-for! reads (:nid (meta form))
  ;; whether that metadata arrived via a text round-trip or, as here,
  ;; direct in-memory construction. Picks the fixture's top-level
  ;; (cooldown/start ...) statement specifically: it is a plain action
  ;; call, not wrapped in a `let` -- unlike a `let`-bound statement (whose
  ;; OWN wrapping form gets stamped too but is never read back by
  ;; compile.clj, only its RHS is -- see nid.clj's own over-stamping
  ;; note), this statement's stamped nid is exactly what compile-node-call
  ;; passes to nid-for!, so it must appear verbatim in the compiled IR."
  (let [raw (surface/read-doc fx/thunder-bolt-text)
        stamped (nid/stamp raw)
        cooldown-stmt (first (filter #(= 'cooldown/start (first %)) (:do stamped)))
        cooldown-nid (:nid (meta cooldown-stmt))
        doc (surface/normalize stamped)
        {:keys [ir diagnostics]} (compile/compile-program doc fx/opts :collect)
        instr-nids (into #{} (map :nid) (mapcat :instrs (:blocks ir)))]
    (is (= [] diagnostics))
    (is (some? cooldown-nid))
    (is (contains? instr-nids cooldown-nid))))
