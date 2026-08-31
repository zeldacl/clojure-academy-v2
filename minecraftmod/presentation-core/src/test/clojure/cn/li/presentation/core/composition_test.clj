(ns cn.li.presentation.core.composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.presentation.core.composition :as composition]))

(defn catalog []
  {:root {:key "root" :blueprint "column"
          :children [{:key "tree" :blueprint "tree-view"
                      :props {:items [1 2] :selected 1 :expanded #{"a"}}
                      :children []}
                     {:key "toolbar" :blueprint "toolbar"
                      :children [{:key "old-button" :blueprint "button"
                                   :props {:action "old"}}] }]}
   :boundaries {"root" {:edit-policy :children}
                "tree" {:edit-policy :replace-only}
                "toolbar" {:edit-policy :children}}
   :blueprints
   {"tree-view" {:props-schema {:items :binding :selected :binding}
                  :slot-schema {}
                  :template {:key "tree-template" :blueprint "column" :children []}}
    "list-view" {:props-schema {:items :binding :selected :binding}
                  :slot-schema {}
                  :template {:key "list-template" :blueprint "column" :children []}}
    "button" {:props-schema {:action :action-id}
               :template {:key "button-template" :blueprint "button" :children []}}
    "column" {:template {:key "column-template" :blueprint "column" :children []}}
    "toolbar" {:template {:key "toolbar-template" :blueprint "row" :children []}}}})

(deftest replace-tree-with-list-preserves-compatible-props-only
  (let [initial (composition/base-composition (catalog))
        result (composition/apply-edit!
                initial
                {:op :replace :target-key "tree" :blueprint "list-view"
                 :props {}})
        tree (get-in (composition/composition-view (:composition result)) [:root :children 0])]
    (is (= :applied (:status result)))
    (is (= "list-view" (:blueprint tree)))
    (is (= {:items [1 2] :selected 1} (:props tree)))
    (is (nil? (get-in tree [:props :expanded])))))

(deftest insert-remove-undo-redo-and-reset-are-session-scoped
  (let [initial (composition/base-composition (catalog))
        inserted (composition/apply-edit!
                  initial
                  {:op :insert :target-key "toolbar" :slot "" :index -1
                   :blueprint "button" :key "new-button"
                   :props {:action "new-action"}})
        after-insert (:composition inserted)
        undone (composition/undo! after-insert)
        redone (composition/redo! (:composition undone))
        removed (composition/apply-edit! (:composition redone) {:op :remove :target-key "new-button"})
        reset (composition/reset-composition! (:composition removed))]
    (is (= :applied (:status inserted)))
    (is (= :applied (:status undone)))
    (is (= :applied (:status redone)))
    (is (= :applied (:status removed)))
    (is (= "new-action"
           (-> redone :composition :root :children second :children second :props :action)))
    (is (= 1 (count (-> reset :root :children second :children))))
    (is (= :applied (:status (composition/apply-edit!
                              reset
                              {:op :insert :target-key "toolbar" :slot "" :index -1
                               :blueprint "button" :key "new-button"
                               :props {:action "new-action"}}))))))

(deftest rejected-edit-is-atomic
  (let [initial (composition/base-composition (catalog))
        rejected (composition/apply-edit!
                  initial
                  {:op :insert :target-key "tree" :slot "" :index -1
                   :blueprint "button" :key "bad" :props {}})]
    (is (= :rejected (:status rejected)))
    (is (= (:root initial) (-> rejected :composition :root)))
    (is (= 0 (:revision rejected)))))
