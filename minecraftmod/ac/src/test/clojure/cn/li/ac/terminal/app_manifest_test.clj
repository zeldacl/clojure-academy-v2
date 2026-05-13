(ns cn.li.ac.terminal.app-manifest-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.app-manifest :as manifest]))

(defn- reset-manifest-fixture
  [f]
  (manifest/reset-defaults!)
  (f)
  (manifest/reset-defaults!))

(use-fixtures :each reset-manifest-fixture)

(deftest default-manifest-non-empty-test
  (is (seq (manifest/list-app-init-symbols))))

(deftest register-app-init-dedupes-test
  (manifest/set-app-init-symbols! [])
  (manifest/register-app-init! 'custom.ns/init!)
  (manifest/register-app-init! 'custom.ns/init!)
  (is (= '[custom.ns/init!] (manifest/list-app-init-symbols))))

(deftest set-app-init-symbols-validates-input-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"must be symbols"
                        (manifest/set-app-init-symbols! ['ok.ns/init! :bad-entry]))))

(deftest reset-defaults-restores-initial-order-test
  (let [defaults (manifest/list-app-init-symbols)]
    (manifest/set-app-init-symbols! ['a/init!])
    (manifest/reset-defaults!)
    (is (= defaults (manifest/list-app-init-symbols)))))
