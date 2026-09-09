(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Pure controller coverage for the V4 free-graph editor.

   Presentation smoke tests cover compiled view routing; this namespace
   covers the controller's V4 geometry and graph edits without a game window."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as editor]))

(defn- graph [& nodes]
  {:nodes (into {} (map (fn [[nid type & kvs]]
                          [nid (into {:nid nid :type type} (apply hash-map kvs))]) nodes))
   :links []})

(deftest compact-and-viewport-screen-coordinates-are-inverted
  (testing "the root inset and compact control column are included"
    (is (= {:x 20.0 :y 12.0}
           (#'editor/screen->canvas-point
            {:canvas-viewport? false :zoom 1.0 :viewport {:x 0.0 :y 0.0}}
            28.0 100.0))))
  (testing "viewport origin and zoom are both inverted"
    (is (= {:x 20.0 :y 12.0}
           (#'editor/screen->canvas-point
            {:canvas-viewport? true :zoom 2.0 :viewport {:x 10.0 :y -8.0}}
            58.0 70.0)))))

(deftest canvas-pointer-bounds-follow-active-mode
  (let [compact {:canvas-viewport? false}
        viewport {:canvas-viewport? true}]
    (is (true? (#'editor/canvas-pointer? compact 8.0 88.0)))
    (is (true? (#'editor/canvas-pointer? compact 472.0 216.0)))
    (is (false? (#'editor/canvas-pointer? compact 472.1 216.0)))
    (is (true? (#'editor/canvas-pointer? viewport 8.0 54.0)))
    (is (true? (#'editor/canvas-pointer? viewport 472.0 332.0)))
    (is (false? (#'editor/canvas-pointer? viewport 8.0 332.1)))))

(deftest screen-delta-is-scaled-only-for-node-movement
  (is (= {:dx 3.0 :dy -2.0}
         (#'editor/screen->canvas-delta {:zoom 2.0} 6.0 -4.0)))
  (is (= {:dx 6.0 :dy -4.0}
         (#'editor/screen->canvas-delta {:zoom 1.0} 6.0 -4.0))))

(deftest fixed-palette-insertion-creates-editable-v4-node
  (let [{:keys [graph nid]} (#'editor/insert-v4-palette-node
                             (graph [:n/start :start])
                             {:id :node/repeat :fixed-type :repeat :params {}}
                             "palette-test")]
    (is (= :repeat (get-in graph [:nodes nid :type])))
    (is (= 1 (get-in graph [:nodes nid :count])))
    (is (= nid (get-in graph [:nodes nid :nid])))))

(deftest component-palette-insertion-preserves-default-input-slots
  (let [{:keys [graph nid]} (#'editor/insert-v4-palette-node
                             (graph [:n/start :start])
                             {:id :math/add
                              :params {:arg0 {:type :double :default 1.5}
                                       :arg1 {:type :long :default 2}}}
                             "palette-test")]
    (is (= :component (get-in graph [:nodes nid :type])))
    (is (= :math/add (get-in graph [:nodes nid :component])))
    (is (= {:arg0 1.5 :arg1 2} (get-in graph [:nodes nid :inputs])))))

(deftest v4-wire-kind-and-target-port-are-derived-from-pins
  (let [g (assoc (graph [:n/lit :literal :value 1]
                        [:n/action :component :component :math/add]
                        [:n/end :end])
                 :links [{:id :e/exec :kind :exec
                          :from [:n/action :out] :to [:n/end :in]}])
        wired (#'editor/connect-v4-wire
               g {:from-nid :n/lit :from-pin :out :from-key :value
                  :to-nid :n/action :to-pin :in :to-key :arg0})
        link (last (:links wired))]
    (is (= :data (:kind link)))
    (is (= [[:n/lit :value] [:n/action :arg0]]
           [(:from link) (:to link)]))))

(deftest remove-v4-node-removes-incident-links-but-protects-sentinels
  (let [g (assoc (graph [:n/start :start]
                        [:n/action :component :component :math/add]
                        [:n/end :end])
                 :links [{:id :e/a :kind :exec :from [:n/start :out] :to [:n/action :in]}
                         {:id :e/b :kind :exec :from [:n/action :out] :to [:n/end :in]}])
        removed (#'editor/remove-v4-node g :n/action)]
    (is (nil? (get-in removed [:nodes :n/action])))
    (is (empty? (:links removed)))
    (is (thrown? clojure.lang.ExceptionInfo (#'editor/remove-v4-node g :n/start)))
    (is (thrown? clojure.lang.ExceptionInfo (#'editor/remove-v4-node g :n/end)))))
