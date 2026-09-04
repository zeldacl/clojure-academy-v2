(ns cn.li.ability.editor.document-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.editor.document :as document]))

(def ^:private sample-text
  "{:doc [\"a note; with a semicolon\"]
 :kind :ability
 :id :t
 :program
 \"{:ability :t :do [(finish {:outcome :performed})]}\"}")

(deftest open-parses-the-field-string-value-test
  (let [doc (document/open sample-text :program)]
    (is (= :t (:ability (:form doc))))
    (is (= [] (:history doc)))
    (is (false? (:dirty? doc)))))

(deftest splice-program-touches-only-the-field-value-test
  (let [spliced (document/splice-program sample-text :program "{:ability :t2 :do []}")]
    (is (not= sample-text spliced))
    (is (.contains spliced ":id :t"))
    (is (.contains spliced "a note; with a semicolon"))
    (is (.contains spliced "{:ability :t2 :do []}"))))

(deftest splice-program-preserves-a-mention-of-the-key-name-inside-doc-prose-test
  ;; A :doc sentence that literally mentions ":program" as prose must not
  ;; be mistaken for the real key -- string-value-span is quote-aware.
  (let [text "{:doc [\"see :program for details\"] :id :t :program \"{:ability :t :do []}\"}"
        spliced (document/splice-program text :program "{:ability :t2 :do []}")]
    (is (.contains spliced "see :program for details"))
    (is (.contains spliced "{:ability :t2 :do []}"))))

(deftest round-trip-with-unchanged-content-is-byte-identical-test
  (let [doc (document/open sample-text :program)
        printed (fn [_form] "{:ability :t :do [(finish {:outcome :performed})]}")
        saved (document/save doc printed)]
    (is (= sample-text (:file-text saved)))
    (is (false? (:dirty? saved)))))

(deftest edit-pushes-history-and-sets-dirty-test
  (let [doc (document/open sample-text :program)
        doc2 (document/edit doc {:ability :t :do []})]
    (is (:dirty? doc2))
    (is (= 1 (count (:history doc2))))
    (is (= (:form doc) (peek (:history doc2))))
    (is (= {:ability :t :do []} (:form doc2)))))

(deftest undo-redo-round-trip-test
  (let [doc (document/open sample-text :program)
        form0 (:form doc)
        doc1 (document/edit doc {:ability :t :do [1]})
        doc2 (document/edit doc1 {:ability :t :do [2]})
        undone-once (document/undo doc2)
        undone-twice (document/undo undone-once)
        redone-once (document/redo undone-twice)]
    (is (= {:ability :t :do [1]} (:form undone-once)))
    (is (= form0 (:form undone-twice)))
    (is (= {:ability :t :do [1]} (:form redone-once)))))

(deftest undo-on-empty-history-is-a-no-op-test
  (let [doc (document/open sample-text :program)]
    (is (= doc (document/undo doc)))))

(deftest redo-on-empty-future-is-a-no-op-test
  (let [doc (document/open sample-text :program)]
    (is (= (:form doc) (:form (document/redo doc))))))
