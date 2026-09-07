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

(defn- sample-value [type]
  (case type
    :vec3 {:x 0.0 :y 0.0 :z 0.0}
    :float 1.0
    :double 1.0
    :int 1
    :long 1
    :string "test"
    :resource-id "test"
    :entity-ref nil
    :bool false
    :any {}
    nil))

(deftest every-v3-document-samples-with-declared-input-shapes
  (let [{:keys [by-id]} (fx-catalog/assemble)]
    (doseq [[effect-id entry] by-id]
      (testing (str effect-id)
        (let [document (:document entry)
              user (into {} (map (fn [[key spec]] [key (sample-value (:type spec))])
                                  (:inputs document)))
              rt (runtime/create-store by-id)
              instance (runtime/ensure! rt [:sample effect-id]
                                        {:effect-id effect-id :seed 1 :user user})]
          (is (= effect-id (:effect-id instance)))
          (is (vector? (get-in (runtime/sample-frame! rt)
                               [[:sample effect-id] :scene]))))))))

(deftest every-v3-document-reaches-frame-abi
  (let [{:keys [by-id]} (fx-catalog/assemble)]
    (doseq [[effect-id entry] by-id]
      (testing (str effect-id)
        (let [document (:document entry)
              user (into {} (map (fn [[key spec]] [key (sample-value (:type spec))])
                                  (:inputs document)))
              rt (runtime/create-client-runtime by-id)
              _ (runtime/ensure! rt [:frame effect-id]
                                 {:effect-id effect-id :seed 1 :user user})
              java-frame (:java-frame (runtime/sample-client-frame! rt))
              raw-scene (get-in (runtime/sample-frame! rt)
                                [[:frame effect-id] :scene])
              output-count (+ (count (.batches java-frame))
                              (count (.outputs java-frame)))]
          (is (= (pos? (count raw-scene)) (pos? output-count))))))))