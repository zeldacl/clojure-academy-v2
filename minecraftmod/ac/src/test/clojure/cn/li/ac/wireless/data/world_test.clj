(ns cn.li.ac.wireless.data.world-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.service.network-command :as network-command]
            [cn.li.ac.wireless.data.world :as world]
            [cn.li.mcmod.platform.nbt :as nbt]))

(use-fixtures
  :each
  (fn [f]
    (world-registry/reset-registry!)
    (try
      (f)
      (finally
        (world-registry/reset-registry!)))))

(defn- test-world
  [world-id]
  {:server-session-id :test-session
   :world-id world-id})

(deftest get-world-data-caches-per-world-test
  (let [w (test-world :world-a)
        a (world/get-world-data w)
        b (world/get-world-data w)]
    (is (identical? a b))
    (is (= w (:world a)))
    (is (= [:test-session :world-a] (:world-key a)))
    (world/remove-world-data! w)))

(deftest world-key-requires-explicit-owner-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires :server-session-id"
                        (world-registry/world-key :legacy-world)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"requires :world-id"
                        (world-registry/world-key {:server-session-id :test-session}))))

  (deftest world-key-isolates-session-and-dimension-test
    (let [world-a {:server-session-id :session-a :dimension-id :overworld :ref 1}
      world-a-new-ref {:server-session-id :session-a :dimension-id :overworld :ref 2}
      world-b {:server-session-id :session-b :dimension-id :overworld :ref 3}
      a (world/get-world-data world-a)
      a2 (world/get-world-data world-a-new-ref)
      b (world/get-world-data world-b)]
    (is (= [:session-a :overworld] (:world-key a)))
    (is (= [:session-a :overworld] (:world-key a2)))
    (is (= [:session-b :overworld] (:world-key b)))
    (is (identical? (:state a) (:state a2)))
    (is (not (identical? (:state a2) (:state b))))
    (is (= 2 (count (world-registry/registry-snapshot))))

    (world/remove-world-data! world-a-new-ref)
    (is (nil? (world/get-world-data-non-create world-a)))
    (is (some? (world/get-world-data-non-create world-b)))))

(deftest create-network-uniqueness-test
  (let [wd (world/create-world-data (test-world :w))
        matrix (vb/create-vmatrix 0 0 0)]
    (is (true? (world/create-network-impl! wd matrix "s1" "pw")))
    (is (false? (world/create-network-impl! wd matrix "s1" "pw2")))))

(deftest create-network-registers-lookups-test
  (let [w (test-world :w-lu)
        wd (world/create-world-data w)
        tiles (atom {[0 0 0] (stubs/fake-matrix {})})
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (world/create-network-impl! wd matrix-vb "reg" "pw")))
        (is (some? (world/get-network-by-matrix wd matrix-vb)))
        (is (some? (world/get-network-by-ssid wd "reg")))
        (is (pos? (count (world-registry/spatial-index wd))))))))

(deftest destroy-network-clears-node-lookups-test
  (let [w (test-world :w-dest)
        wd (world/create-world-data w)
        m (stubs/fake-matrix {:capacity 4})
        n (stubs/mutable-node {})
        tiles (atom {[0 0 0] m [3 0 0] n})
        matrix-vb (vb/create-vmatrix 0 0 0)
        node-vb (vb/create-vnode 3 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (world/create-network-impl! wd matrix-vb "dnet" "p")))
        (let [net (world/get-network-by-ssid wd "dnet")]
          (is (true? (network-membership/add-node! net node-vb "p")))
          (is (some? (get (world-registry/net-lookup wd) node-vb)))
          (world/destroy-network-impl! wd net)
          (is (nil? (get (world-registry/net-lookup wd) node-vb)))
          (is (nil? (world/get-network-by-ssid wd "dnet"))))))))

(deftest change-network-ssid-refreshes-string-lookup-test
  (let [wd (world/create-world-data (test-world :w-rename))
        matrix-vb (vb/create-vmatrix 0 0 0)]
    (is (true? (world/create-network-impl! wd matrix-vb "old" "p")))
    (let [network (world/get-network-by-ssid wd "old")]
      (is (true? (network-command/change-network-ssid! network "new")))
      (is (= "new" (network-state/get-ssid network)))
      (is (nil? (world/get-network-by-ssid wd "old")))
      (is (identical? network (world/get-network-by-ssid wd "new"))))))

(deftest world-lifecycle-saved-data-restores-active-world-state-test
  (let [world-id (test-world :w-lifecycle)
        matrix-vb (vb/create-vmatrix 0 0 0)
        node-vb (vb/create-vnode 3 0 0)
        node-conn-vb (vb/->VBlock 3 0 0 :node-conn true)
        gen-vb (vb/->VBlock 5 0 0 :generator true)
        tiles (atom {[0 0 0] (stubs/fake-matrix {})
                     [3 0 0] (stubs/mutable-node {})
                     [5 0 0] (stubs/generator-stub {})})]
    (stubs/with-tile-world tiles
      (fn []
        (let [wd (world/create-world-data world-id)]
          (is (true? (world/create-network-impl! wd matrix-vb "persist" "pw")))
          (is (true? (network-membership/add-node! (world/get-network-by-ssid wd "persist") node-vb "pw")))
          (let [conn (world/ensure-node-connection! wd node-conn-vb)]
            (is (true? (node-conn/add-generator! conn gen-vb))))
          (world/register-world-data! world-id wd)
          (let [saved (world/on-world-save world-id)]
            (world/remove-world-data! world-id)
            (is (nil? (world/get-world-data-non-create world-id)))
            (let [restored (world/on-world-load world-id saved)]
              (is (not (identical? wd restored)))
              (let [network (world/get-network-by-matrix restored matrix-vb)
                    conn (world/get-node-connection restored gen-vb)]
                (is (= "persist" (network-state/get-ssid network)))
                (is (= [node-vb] (network-state/get-nodes network)))
                (is (identical? network (world/get-network-by-node restored node-vb)))
                (is (= [gen-vb] (node-conn/get-generators conn)))))))))))

(deftest world-lifecycle-skips-invalid-saved-wireless-entries-test
  (let [world-id (test-world :w-lifecycle-corrupt)
        tiles (atom {})]
    (stubs/with-tile-world tiles
      (fn []
        (let [payload (nbt/create-nbt-compound)
              networks (nbt/create-nbt-list)
              connections (nbt/create-nbt-list)]
          ;; Missing required nested fields (`matrix`, `node`, receivers/generators).
          ;; A single corrupt entry should not prevent the world from loading.
          (nbt/nbt-append! networks (nbt/create-nbt-compound))
          (nbt/nbt-append! connections (nbt/create-nbt-compound))
          (nbt/nbt-set-tag! payload "networks" networks)
          (nbt/nbt-set-tag! payload "connections" connections)
          (let [restored (world/on-world-load world-id (world/->WiSavedDataWrapper nil payload))]
            (is (identical? restored (world/get-world-data-non-create world-id)))
            (is (empty? (world-registry/networks restored)))
            (is (empty? (world-registry/connections restored)))))))))
