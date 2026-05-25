(ns cn.li.ac.block.ability-interferer.gui-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.ability-interferer.gui]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.network.client :as net-client]))

(defn- pv [sym]
  (or (ns-resolve 'cn.li.ac.block.ability-interferer.gui sym)
      (throw (ex-info "Missing private var" {:sym sym}))))

(deftest add-whitelist-name-requires-explicit-input-test
  (testing "blank input does not change whitelist"
    (is (= ["Alice"]
           ((pv 'add-whitelist-name) ["Alice"] "   "))))
  (testing "new name is normalized and sorted"
    (is (= ["Alice" "Bob"]
           ((pv 'add-whitelist-name) ["Bob"] " Alice ")))))

(deftest remove-focused-whitelist-name-removes-selection-only-test
  (testing "no focus keeps whitelist unchanged"
    (is (= ["Alice" "Bob"]
           ((pv 'remove-focused-whitelist-name) ["Alice" "Bob"] nil))))
  (testing "focused entry is removed"
    (is (= ["Alice"]
           ((pv 'remove-focused-whitelist-name) ["Alice" "Bob"] "Bob")))))

(deftest visible-whitelist-window-clamps-scroll-test
  (let [window ((pv 'visible-whitelist-window)
                 ["A" "B" "C" "D" "E"]
                 999
                 4)]
    (is (= 1 (:start window)))
    (is (= 1 (:max-scroll window)))
    (is (= ["B" "C" "D" "E"] (:names window)))))

(deftest request-set-range-keeps-authoritative-range-until-sync-test
  (let [calls (atom [])
        container {:tile-entity :tile
                   :range (atom 10.0)
                   :pending-range (atom nil)}]
    (with-redefs [sync-handler/tile-pos-payload (fn [_] {:pos-x 1 :pos-y 2 :pos-z 3})
            msg-registry/msg (fn [_ action] [:ability-interferer action])
            net-client/send-to-server (fn [msg-id payload]
                          (swap! calls conj [msg-id payload]))]
      ((pv 'request-set-range!) container 42.0)
      (is (= 10.0 @(:range container)))
      (is (= 42.0 @(:pending-range container)))
      (is (= 1 (count @calls)))
      (is (= 42.0 (get-in @calls [0 1 :range]))))))

(deftest request-set-enabled-keeps-authoritative-enabled-until-sync-test
  (let [calls (atom [])
        container {:tile-entity :tile
                   :enabled (atom false)
                   :pending-enabled (atom nil)}]
    (with-redefs [sync-handler/tile-pos-payload (fn [_] {:pos-x 1 :pos-y 2 :pos-z 3})
            msg-registry/msg (fn [_ action] [:ability-interferer action])
            net-client/send-to-server (fn [msg-id payload]
                          (swap! calls conj [msg-id payload]))]
      ((pv 'request-set-enabled!) container true)
      (is (false? @(:enabled container)))
      (is (true? @(:pending-enabled container)))
      (is (= 1 (count @calls)))
      (is (true? (get-in @calls [0 1 :enabled]))))))

(deftest after-sync-or-apply-clears-pending-and-refreshes-view-test
  (let [refresh-calls (atom 0)
        container {:whitelist (atom ["Alice"])
                   :whitelist-edit (atom "")
                   :pending-range (atom 42.0)
                   :pending-enabled (atom true)
                   :refresh-whitelist-view (atom (fn [] (swap! refresh-calls inc)))}]
    ((pv 'after-sync-or-apply!) container nil)
    (is (= "Alice" @(:whitelist-edit container)))
    (is (nil? @(:pending-range container)))
    (is (nil? @(:pending-enabled container)))
    (is (= 1 @refresh-calls))))

(deftest add-input-widget-open-close-lifecycle-test
  (let [panel (cgui-core/create-widget :name "panel_whitelist" :size [160 80])
        input-ref (atom nil)
        container {:add-input-widget input-ref
                   :whitelist (atom [])}]
    ((pv 'open-add-input-widget!) panel container)
    (is (some? @input-ref))
    (is (= 1 (count (cgui-core/get-widgets panel))))
    ((pv 'close-add-input-widget!) panel container)
    (is (nil? @input-ref))
    (is (= 0 (count (cgui-core/get-widgets panel))))))
