(ns cn.li.ac.vfx.fx-catalog-v3-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.vfx.fx-catalog :as fx-catalog]
            [cn.li.vfx.runtime :as runtime]))

(deftest assembles-all-v3-effects-without-manifest
  (let [{:keys [effects by-id resource-count]} (fx-catalog/assemble)]
    (is (= 36 resource-count))
    (is (= 36 (count effects)))
    (is (= 36 (count by-id)))
    (is (= :ac/vfx-v3 (get-in by-id [:beam-session :document :schema])))
    (is (= :ray-beam
           (get-in by-id [:beam-session :document :system :render 0 :component])))))

(deftest v3-document-compiles-through-runtime
  (let [{:keys [by-id]} (fx-catalog/assemble)
        rt (runtime/create-store by-id)
        instance (runtime/ensure! rt [:probe]
                                  {:effect-id :beam-session
                                   :seed 7
                                   :user {:start {:x 0.0 :y 0.0 :z 0.0}
                                          :end {:x 1.0 :y 0.0 :z 0.0}
                                          :grow-ticks 2
                                          :style :thin}})
        sampled (runtime/sample-frame! rt)]
    (is (= :beam-session (:effect-id instance)))
    (is (= :ray-beam (get-in sampled [[:probe] :scene 0 :kind])))
    (testing "V3 document is retained for editor/catalog inspection, not a source string"
      (is (map? (get-in by-id [:beam-session :document])))
      (is (nil? (get-in by-id [:beam-session :scene]))))))

(deftest every-v3-document-enters-runtime-compiler
  (let [{:keys [by-id]} (fx-catalog/assemble)]
    (doseq [[effect-id _] by-id]
      (testing (str effect-id)
        (let [rt (runtime/create-store by-id)]
          (is (= effect-id
                 (:effect-id (runtime/ensure! rt [:compile effect-id]
                                               {:effect-id effect-id :user {}})))))))))
