(ns cn.li.ac.terminal.media-backend-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.terminal.apps.media-backend :as mb]))

(defn- reset-fixture [f]
  (mb/call-with-playback-runtime
    (mb/create-playback-runtime)
    (fn []
      (mb/reset-playback-states-for-test!)
      (try
        (f)
        (finally
          (mb/reset-playback-states-for-test!))))))

(use-fixtures :each reset-fixture)

(def ^:private owner {:client-session-id :session-a :screen-id :media :profile-id :test})

(deftest volume-and-track-selection-test
  (is (= 1.0 (mb/set-volume! owner 2.0)))
  (is (= 0.0 (mb/set-volume! owner -1.0)))
  (is (= :sisters-noise (:id (mb/select-track! owner 0))))
  (is (contains? #{:only-my-railgun :level5-judgelight :sisters-noise}
                 (:id (mb/next-track! owner)))))

(deftest pause-toggle-state-test
  (with-redefs [client-sounds/queue-sound-effect! (fn [& _] nil)]
    (is (= :playing (mb/toggle-pause! owner)))
    (is (= :paused (mb/toggle-pause! owner)))))

(deftest media-owner-key-fails-without-profile-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Media backend owner requires :profile-id"
                        (mb/playback-state-snapshot {:client-session-id :session-a
                                                     :screen-id :media-player}))))

(deftest playback-state-isolated-by-owner-test
  (let [owner-a {:client-session-id :session-a :screen-id :media :profile-id :a}
        owner-b {:client-session-id :session-a :screen-id :media :profile-id :b}]
    (is (= 0.25 (mb/set-volume! owner-a 0.25)))
    (is (= :level5-judgelight (:id (mb/select-track! owner-b 2))))
    (is (= 0.25 (:volume (mb/playback-state-snapshot owner-a))))
    (is (= 1.0 (:volume (mb/playback-state-snapshot owner-b))))
    (is (= :sisters-noise (get-in (mb/playback-state-snapshot owner-a) [:track :id])))
    (is (= :level5-judgelight (get-in (mb/playback-state-snapshot owner-b) [:track :id])))))

(deftest playback-queue-uses-owner-state-test
  (let [owner-a {:client-session-id :session-a :screen-id :media :profile-id :a}
        owner-b {:client-session-id :session-a :screen-id :media :profile-id :b}
        queued (atom [])]
    (mb/set-volume! owner-a 0.2)
    (mb/select-track! owner-b 1)
    (with-redefs [client-sounds/queue-sound-effect! (fn [owner event]
                                                      (swap! queued conj {:owner owner
                                                                         :event event}))]
      (is (= :sisters-noise (:id (mb/play-current! owner-a))))
      (is (= :only-my-railgun (:id (mb/play-current! owner-b)))))
    (is (= [owner-a owner-b] (mapv :owner @queued)))
    (is (= ["my_mod:em.arc_strong" "my_mod:em.railgun"] (mapv (comp :sound-id :event) @queued)))
    (is (< (Math/abs (- 0.2 (double (get-in (first @queued) [:event :volume])))) 1.0E-6))
    (is (< (Math/abs (- 1.0 (double (get-in (second @queued) [:event :volume])))) 1.0E-6))))
