(ns cn.li.ability.registry-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.compose :as compose]
            [cn.li.ability.registry :as registry]
            [cn.li.node.api :as node-api]))

(defn- bundle
  "Mirrors ac/final_catalog.clj's assemble: compose-catalog produces the
   bundle, then :content-hash is computed separately over
   catalog-fingerprint-input and assoc'd on -- compose-catalog itself never
   sets it. A fixture that skipped this step would make every bundle hash
   to the same nil-hashes-map, unable to distinguish content."
  [content-id ability-ids effect-ids]
  (let [b (compose/compose-catalog
           content-id
           {:descriptors {:combat/damage {:id :combat/damage}}}
           {:registrations (mapv (fn [id] {:id id :graph {}}) ability-ids)}
           {:effects (into {} (map (fn [id] [id {:id id}])) effect-ids)})]
    (assoc b :content-hash (node-api/content-hash (compose/catalog-fingerprint-input b)))))

(deftest merges-two-tenants-with-distinct-ids-test
  (let [registry (-> (registry/empty-registry)
                     (registry/add-bundle (bundle :ac [:groundshock] [:arc-ring]))
                     (registry/add-bundle (bundle :bc [:bc-ability] [:bc-effect]))
                     registry/freeze)]
    (is (= [:ac :bc] (registry/content-ids registry)))
    (is (some? (registry/ability registry :groundshock)))
    (is (some? (registry/ability registry :bc-ability)))
    (is (some? (registry/effect registry :arc-ring)))
    (is (some? (registry/effect registry :bc-effect)))
    (is (nil? (registry/ability registry :unknown)))))

(deftest rejects-duplicate-content-id-test
  (let [registry (registry/add-bundle (registry/empty-registry) (bundle :ac [:groundshock] []))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate content-id"
                          (registry/add-bundle registry (bundle :ac [:other] []))))))

(deftest rejects-ability-id-collision-across-tenants-and-names-both-owners-test
  (let [registry (registry/add-bundle (registry/empty-registry) (bundle :ac [:groundshock] []))
        error (try
                (registry/add-bundle registry (bundle :bc [:groundshock] []))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is error)
    (is (= [:ac :bc] (:owners (ex-data error))))
    (is (= :groundshock (:id (ex-data error))))))

(deftest rejects-effect-id-collision-across-tenants-test
  (let [registry (registry/add-bundle (registry/empty-registry) (bundle :ac [] [:arc-ring]))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"effect id collision"
                          (registry/add-bundle registry (bundle :bc [] [:arc-ring]))))))

(deftest freeze-then-add-bundle-throws-test
  (let [registry (registry/freeze (registry/add-bundle (registry/empty-registry) (bundle :ac [] [])))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry is frozen"
                          (registry/add-bundle registry (bundle :bc [] []))))))

(deftest content-hash-is-deterministic-and-distinguishes-registries-test
  (let [r1 (registry/freeze (registry/add-bundle (registry/empty-registry) (bundle :ac [:groundshock] [])))
        r2 (registry/freeze (registry/add-bundle (registry/empty-registry) (bundle :ac [:groundshock] [])))
        r3 (registry/freeze (registry/add-bundle (registry/empty-registry) (bundle :ac [:railgun] [])))]
    (is (string? (registry/content-hash r1)))
    (is (= (registry/content-hash r1) (registry/content-hash r2)))
    (is (not= (registry/content-hash r1) (registry/content-hash r3)))))

(deftest descriptor-in-finds-tenants-own-descriptor-test
  (let [registry (registry/freeze (registry/add-bundle (registry/empty-registry) (bundle :ac [] [])))]
    (is (= {:id :combat/damage} (registry/descriptor-in registry :ac :combat/damage)))
    (is (nil? (registry/descriptor-in registry :ac :unknown/id)))))
