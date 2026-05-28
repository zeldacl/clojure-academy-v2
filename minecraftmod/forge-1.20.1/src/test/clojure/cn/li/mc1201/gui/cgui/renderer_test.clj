(ns cn.li.mc1201.gui.cgui.renderer-test
  (:require [clojure.core :as core]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mc1201.gui.cgui.renderer :as renderer]))

(use-fixtures :each
  (fn [f]
    (renderer/call-with-cgui-renderer-runtime
      (renderer/create-cgui-renderer-runtime)
      (fn []
        (f)))))

(deftest get-texture-size-caches-resource-lookup-test
  (let [lookups (atom 0)]
    (core/with-redefs-fn
      {#'renderer/get-texture-size-from-resource (fn [_resource-location]
                                                   (swap! lookups inc)
                                                   [64 32])}
      (fn []
        (is (= [64 32]
               (#'renderer/get-texture-size "test:texture")))
        (is (= [64 32]
               (#'renderer/get-texture-size "test:texture")))
        (is (= 1 @lookups))
        (is (= {"test:texture" [64 32]}
               (renderer/texture-size-cache-snapshot)))))))

(deftest clear-texture-size-cache-test
  (renderer/reset-texture-size-cache-for-test! {"test:texture" [16 16]})
  (renderer/clear-texture-size-cache!)
  (is (= {}
         (renderer/texture-size-cache-snapshot))))

(deftest cgui-renderer-runtime-isolation-test
  (let [runtime-b (renderer/create-cgui-renderer-runtime)]
    (renderer/reset-texture-size-cache-for-test! {"root" [16 16]})
    (renderer/call-with-cgui-renderer-runtime
      runtime-b
      (fn []
        (renderer/reset-texture-size-cache-for-test! {"overlay" [32 32]})
        (is (= {"overlay" [32 32]}
               (renderer/texture-size-cache-snapshot)))))
    (is (= {"root" [16 16]}
           (renderer/texture-size-cache-snapshot)))))
