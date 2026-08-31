(ns cn.li.presentation.core.runtime-composition-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.presentation.core.runtime :as runtime])
  (:import [cn.li.mcmod.runtime UiEditCommand$Insert UiEditCommand$Replace
            UiEditCommand$Remove]))

(def base-view
  {:root {:key "root" :blueprint "column"
          :children [{:key "tree" :blueprint "tree-view"
                      :props {:items [1 2] :selected 1 :expanded #{"a"}}
                      :children []}
                     {:key "toolbar" :blueprint "toolbar"
                      :children [{:key "old-button" :blueprint "button"
                                   :props {:action "old"}}]}]}
   :boundaries {"root" {:edit-policy :children}
                "tree" {:edit-policy :replace-only}
                "toolbar" {:edit-policy :children}}
   :blueprints
   {"tree-view" {:props-schema {:items :binding :selected :binding}
                  :template {:key "tree-template" :blueprint "column" :children []}}
    "list-view" {:props-schema {:items :binding :selected :binding}
                  :template {:key "list-template" :blueprint "column" :children []}}
    "button" {:props-schema {:action :action-id}
               :template {:key "button-template" :blueprint "button" :children []}}
    "column" {:template {:key "column-template" :blueprint "column" :children []}}
    "toolbar" {:template {:key "toolbar-template" :blueprint "row" :children []}}}})

(defn mounted-runtime []
  (let [runtime (runtime/create-runtime)
        mount (runtime/mount! runtime {:host {:stage :hud}
                                       :artifact {:semantics {}}
                                       :base-view base-view})]
    [runtime mount]))

(deftest runtime-routes-neutral-commands-and-restores-session
  (let [[runtime mount] (mounted-runtime)
        replace (UiEditCommand$Replace.
                 "tree" "list-view"
                 (java.util.Map/of "items" [1 2])
                 (java.util.Map/of))
        insert (UiEditCommand$Insert.
                "toolbar" "" -1 "button" "new-button"
                (java.util.Map/of "action" "new-action")
                (java.util.Map/of))]
    (is (= "tree" (-> (runtime/composition runtime mount) :root :children first :key)))
    (is (= "APPLIED" (.name (.status (runtime/apply-edit! runtime mount replace)))))
    (is (= "list-view" (-> (runtime/composition runtime mount) :root :children first :blueprint)))
    (is (= "APPLIED" (.name (.status (runtime/apply-edit! runtime mount insert)))))
    (is (= "new-button"
           (-> (runtime/composition runtime mount) :root :children second :children second :key)))
    (is (= "APPLIED" (.name (.status (runtime/reset-edits! runtime mount)))))
    (is (= "tree-view" (-> (runtime/composition runtime mount) :root :children first :blueprint)))
    (is (= 1 (count (-> (runtime/composition runtime mount) :root :children second :children))))))

(deftest runtime-unmount-drops-ephemeral-composition
  (let [[runtime mount] (mounted-runtime)
        remove (UiEditCommand$Remove. "tree")]
    (is (= "APPLIED" (.name (.status (runtime/apply-edit! runtime mount remove)))))
    (runtime/unmount! runtime mount)
    (let [mount2 (runtime/mount! runtime {:host {:stage :hud}
                                          :artifact {:semantics {}}
                                          :base-view base-view})]
      (is (= "tree" (-> (runtime/composition runtime mount2) :root :children first :key))))))

(deftest pui4-artifact-mount-builds-editable-base-view
  (let [rt (runtime/create-runtime)
        artifact {:magic :pui4 :schema 4 :view-id :academy/test/pui4
                  :nodes {:key :root :type :column :children
                          [{:key :tree :type :scroll
                            :props {:items [:state :items] :selected [:state :selected]}}]}
                  :blueprint-catalog
                  {1 {:blueprint-id 1 :primitive :scroll :edit-policy :replace-only
                      :props-schema {:items :binding :selected :binding} :slot-schema {}}
                   2 {:blueprint-id 2 :primitive :scroll :edit-policy :replace-only
                      :props-schema {:items :binding :selected :binding} :slot-schema {}}}
                  :boundaries {"tree" {:blueprint-id 1 :edit-policy :replace-only
                                        :props-schema {:items :binding :selected :binding}
                                        :slot-schema {}}}}
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :artifact artifact})
        command (UiEditCommand$Replace. "tree" "2"
                                        (java.util.Map/of)
                                        (java.util.Map/of))
        edit-result (runtime/apply-edit! rt mount command)]
    (is (= "APPLIED" (.name (.status edit-result))))
    (is (= "2" (-> (runtime/composition rt mount) :root :children first :blueprint)))))
