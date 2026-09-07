(ns cn.li.ac.ability.skills-catalog-v3-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.ac.ability.skills-catalog-v3 :as catalog]
            [cn.li.combat.api :as combat-api]
            [cn.li.node.document :as document]
            [cn.li.node.document-migrate :as migrate]
            [cn.li.node.api :as node-api]))

(deftest directory-catalog-compiles-one-document
  (let [{:keys [skills by-id resource-count]}
        (catalog/assemble {:resource-root "ac/skills-v3"})
        skill (get by-id :ac.test/catalog-smoke)]
    ;; The test classpath also includes the 50 production documents; this
    ;; assertion only verifies that the directory loader can discover the
    ;; isolated smoke document and compile it.
    (is (<= 1 resource-count))
    (is (<= 1 (count skills)))
    (is (= :ac.test/catalog-smoke (:id skill)))
    (is (= {:activate :activation/start}
           (:entry-triggers (:ir skill))))
    (is (string? (:semantic-digest skill)))))

(deftest migrates-real-arc-gen-resource
  (let [old (binding [*read-eval* false]
              (edn/read-string (slurp (io/resource "ac/skills/arc_gen.edn"))))
        v3 (migrate/migrate-skill old)]
    (document/validate-document! v3)
    (is (= :arc-gen (:id v3)))
    (is (pos? (count (get-in v3 [:entries :default :do]))))))

(deftest migrates-all-old-skill-resources
  (let [resources (->> (catalog/resource-names "ac/skills")
                       (remove #(clojure.string/ends-with? % "/manifest.edn")))
        old-by-resource
        (into {}
              (map (fn [resource]
                     [resource (binding [*read-eval* false]
                                 (edn/read-string (slurp (io/resource resource))))])
              resources))
        manifest (binding [*read-eval* false]
                   (edn/read-string (slurp (io/resource "ac/skills/manifest.edn"))))
        registrations (:documents manifest)
        compile-opts {:vocab combat-api/skill-vocab
                      :capabilities combat-api/skill-capability-type
                      :fns combat-api/skill-lib-fns}
        migrated (mapv (fn [registration]
                         (let [resource (:resource registration)
                               old (get old-by-resource resource)
                               v3 (migrate/migrate-registration registration old)
                               {:keys [diagnostics]}
                               (node-api/compile-skill-document! v3 compile-opts :collect)]
                           (document/validate-document! v3)
                           (when (seq diagnostics)
                             (throw (ex-info "migrated skill does not compile"
                                             {:resource resource :document-id (:id v3)
                                              :diagnostics diagnostics})))
                           v3))
                       registrations)]
    (is (= 39 (count resources)))
    (is (= 50 (count registrations)))
    (is (= 50 (count migrated)))
    (is (= 50 (count (set (map :id migrated)))))))
