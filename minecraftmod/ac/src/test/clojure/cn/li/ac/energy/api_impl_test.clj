(ns cn.li.ac.energy.api-impl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.ac.energy.api.impl :as energy-api]
            [cn.li.ac.energy.api.protocol :as proto]
            [cn.li.ac.energy.domain.container :as container]))

(defn- reset-default-system-fixture [f]
  (let [system (energy-api/energy-system)]
    (proto/admin-reset-energy-system system)
    (try
      (f)
      (finally
        (proto/admin-reset-energy-system system)))))

(use-fixtures :each reset-default-system-fixture)

(deftest atom-container-provider-registration-and-subscription-test
  (let [system (energy-api/energy-system)
        tank (atom (container/energy-container 100.0 10.0))
        seen (atom [])]
    (testing "registration delegates to provider registry"
      (is (= :tank (energy-api/register-provider! :tank tank)))
      (is (= [:tank] (proto/list-energy-providers system)))
      (is (= 10.0 (proto/get-energy system :tank)))
      (is (= 100.0 (proto/get-capacity system :tank))))
    (testing "atom-backed containers can be mutated and notify subscribers"
      (let [sid (proto/subscribe-to-changes system :tank (fn [old new]
                                                           (swap! seen conj [old new])))]
        (is (= {:success true :reason "ok"}
               (proto/set-energy system :tank 60.0)))
        (is (= 60.0 (container/get-current @tank)))
        (is (= [[10.0 60.0]] @seen))
        (is (nil? (proto/unsubscribe-from-changes system sid)))
        (is (= {:success true :reason "ok"}
               (proto/set-energy system :tank 30.0)))
        (is (= [[10.0 60.0]] @seen))))
    (testing "admin dump and unregister remain available through impl facade"
      (is (= {:tank {:kind :container :energy 30.0 :capacity 100.0}}
             (proto/admin-dump-state system)))
      (is (nil? (energy-api/unregister-provider! :tank)))
      (is (= [] (proto/list-energy-providers system))))))

(deftest immutable-container-provider-is-readable-but-not-settable-test
  (let [system (energy-api/energy-system)
        tank (container/energy-container 50.0 12.0)]
    (is (= :immutable (energy-api/register-provider! :immutable tank)))
    (is (= 12.0 (proto/get-energy system :immutable)))
    (is (= {:success false
            :reason "container is immutable unless registered with an atom ref"}
           (proto/set-energy system :immutable 20.0)))))
