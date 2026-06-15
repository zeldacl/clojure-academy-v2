(ns cn.li.ac.item.energy-items-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.item.energy-items :as energy-items]))

(deftest portable-developer-opens-cgui-screen-test
  (testing "portable developer opens CGUI developer screen (page_developer.xml)"
    (let [calls (atom [])]
      (with-redefs [requiring-resolve
                    (fn [sym]
                      (cond
                        (= sym 'cn.li.ac.item.developer-portable/create-screen)
                        (fn [player]
                          (swap! calls conj {:step :create-screen :player player})
                          {:type :cgui-screen :cgui :mock-root :session-id "mock-session"})

                        (= sym 'cn.li.mc1201.gui.screen.cgui-screen-host/open-cgui-screen!)
                        (fn [root session-id opts]
                          (swap! calls conj {:step :open-cgui-screen
                                             :root root
                                             :session-id session-id
                                             :opts opts}))

                        :else
                        (throw (Exception. (str "Unknown requiring-resolve: " sym))))))]
        (is (= {:consume? true}
               (#'energy-items/open-portable-developer! {:player :player-1
                                                         :side :client})))
        (is (= [{:step :create-screen :player :player-1}
                {:step :open-cgui-screen
                 :root :mock-root
                 :session-id "mock-session"
                 :opts {:title "Portable Developer"}}]
               @calls)))))

(deftest portable-developer-skips-on-server-side-test
  (testing "portable developer does nothing on server side"
    (is (= {:consume? true}
           (#'energy-items/open-portable-developer! {:player :player-1
                                                     :side :server})))))