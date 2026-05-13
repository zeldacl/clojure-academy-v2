(ns cn.li.ac.terminal.init-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.terminal.app-manifest :as manifest]
            [cn.li.ac.terminal.init :as terminal-init]))

(deftest register-apps-uses-manifest-symbols-test
  (let [resolved-calls (atom [])
        invoked (atom [])]
    (with-redefs [manifest/list-app-init-symbols
                  (fn [] '[demo.a/init! demo.b/init!])
                  requiring-resolve
                  (fn [sym]
                    (swap! resolved-calls conj sym)
                    (fn [] (swap! invoked conj sym)))]
      (terminal-init/register-apps!)
      (is (= '[demo.a/init! demo.b/init!] @resolved-calls))
      (is (= '[demo.a/init! demo.b/init!] @invoked)))))
