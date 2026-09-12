(ns cn.li.node.compile-vec-shape-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.node.surface :as surface]
            [cn.li.node.compile :as compile]
            [cn.li.node.test-fixtures :as fx]))

(deftest malformed-vec3-literal-is-a-compile-error-test
  (testing "a concrete vector with the wrong shape must fail before the
            emitter can destructure it and produce a runtime vec3 error"
    (let [doc (surface/parse
               "{:ability :bad-vec3 :tunables {}
                 :do [(let v (vec3/add [1.0 2.0] [0.0 0.0 0.0]))
                      (finish {:outcome :performed})]}")]
      (try
        (compile/compile! doc fx/opts)
        (is false "expected compile! to throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :invalid-literal-shape (:code (ex-data e)))))))))
