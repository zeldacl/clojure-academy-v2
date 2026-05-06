(ns cn.li.mcmod.util.render-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.util.render :as render]
            [cn.li.mcmod.util.log :as log]))

(defn- reset-render-state! [f]
  (reset! (var-get #'render/texture-binder*) nil)
  (reset! (var-get #'render/texture-binder-warned*) false)
  (f)
  (reset! (var-get #'render/texture-binder*) nil)
  (reset! (var-get #'render/texture-binder-warned*) false))

(use-fixtures :each reset-render-state!)

(deftest texture-binder-contract-test
  (testing "registered binder receives texture argument"
    (let [calls (atom [])]
      (render/register-texture-binder! (fn [tex] (swap! calls conj tex)))
      (render/bind-texture "a/b")
      (is (= ["a/b"] @calls))))
  (testing "missing binder warns once only"
    (let [warns (atom [])]
      (with-redefs [log/warn (fn [& xs] (swap! warns conj xs))]
        (render/bind-texture "x")
        (render/bind-texture "y")
        (is (= 1 (count @warns)))))))

(deftest with-matrix-contract-test
  (testing "with-matrix pushes then pops on normal path"
    (let [calls (atom [])]
      (with-redefs [render/gl-push-matrix (fn [] (swap! calls conj :push))
                    render/gl-pop-matrix (fn [] (swap! calls conj :pop))]
        (render/with-matrix
          (swap! calls conj :body))
        (is (= [:push :body :pop] @calls)))))
  (testing "with-matrix pops even when body throws"
    (let [calls (atom [])]
      (with-redefs [render/gl-push-matrix (fn [] (swap! calls conj :push))
                    render/gl-pop-matrix (fn [] (swap! calls conj :pop))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                              (render/with-matrix
                                (throw (ex-info "boom" {})))))
        (is (= [:push :pop] @calls))))))

(deftest render-time-contract-test
  (testing "get-render-time returns seconds as double and does not go backwards"
    (let [t1 (render/get-render-time)
          t2 (render/get-render-time)]
      (is (double? t1))
      (is (double? t2))
      (is (<= t1 t2)))))
