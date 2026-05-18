(ns cn.li.mcmod.gui.spec-test
  (:require [clojure.test :refer :all]
            [cn.li.mcmod.gui.spec :as gui-spec]))

(deftest create-block-gui-spec-test
  (testing "standard block GUI options are grouped into a GuiSpec"
    (let [slot-layout [{:index 0 :x 8 :y 18}]
          spec (gui-spec/create-block-gui-spec
                 "test-block-gui"
                 {:gui-id 99
                  :display-name "Test Block GUI"
                  :gui-type :test-block
                  :registry-name "test_block_gui"
                  :screen-factory-fn-kw :create-test-block-screen
                  :slot-layout slot-layout
                  :container-predicate ::container-predicate
                  :container-fn ::container-fn
                  :screen-fn ::screen-fn
                  :tick-fn ::tick-fn
                  :sync-get ::sync-get
                  :sync-apply ::sync-apply
                  :payload-sync-apply-fn ::payload-sync-apply
                  :validate-fn ::validate
                  :close-fn ::close
                  :button-click-fn ::button-click
                  :slot-count-fn ::slot-count
                  :slot-get-fn ::slot-get
                  :slot-set-fn ::slot-set
                  :slot-can-place-fn ::slot-can-place
                  :slot-changed-fn ::slot-changed})]
      (is (= "test-block-gui" (:id spec)))
      (is (= 99 (:gui-id spec)))
      (is (= {:display-name "Test Block GUI"
              :gui-type :test-block
              :registry-name "test_block_gui"
              :screen-factory-fn-kw :create-test-block-screen
              :slot-layout slot-layout}
             (select-keys (:registration spec)
                          [:display-name :gui-type :registry-name :screen-factory-fn-kw :slot-layout])))
      (is (= {:container-predicate ::container-predicate
              :container-fn ::container-fn
              :screen-fn ::screen-fn
              :tick-fn ::tick-fn}
             (select-keys (:lifecycle spec)
                          [:container-predicate :container-fn :screen-fn :tick-fn])))
      (is (= {:sync-get ::sync-get
              :sync-apply ::sync-apply
              :payload-sync-apply-fn ::payload-sync-apply}
             (select-keys (:sync spec)
                          [:sync-get :sync-apply :payload-sync-apply-fn])))
      (is (= {:validate-fn ::validate
              :close-fn ::close
              :button-click-fn ::button-click}
             (select-keys (:operations spec)
                          [:validate-fn :close-fn :button-click-fn])))
      (is (= {:slot-count-fn ::slot-count
              :slot-get-fn ::slot-get
              :slot-set-fn ::slot-set
              :slot-can-place-fn ::slot-can-place
              :slot-changed-fn ::slot-changed}
             (select-keys (:slot-operations spec)
                          [:slot-count-fn :slot-get-fn :slot-set-fn :slot-can-place-fn :slot-changed-fn]))))))
