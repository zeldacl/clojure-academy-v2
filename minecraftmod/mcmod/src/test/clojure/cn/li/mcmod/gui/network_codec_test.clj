(ns cn.li.mcmod.gui.network-codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.network-codec :as codec]))

(defn- new-buffer []
  (atom {:writes [] :reads []}))

(defn- with-reads [buf values]
  (swap! buf assoc :reads (vec values))
  buf)

(defn- push-write! [buf kind value]
  (swap! buf update :writes conj [kind value]))

(defn- pop-read! [buf]
  (let [v (first (:reads @buf))]
    (swap! buf update :reads subvec 1)
    v))

(deftest write-value-type-tag-contract-test
  (testing "write-value* writes type tags and payload by value type"
    (let [buf (new-buffer)
          wb (fn [b v] (push-write! b :byte v))
          wi (fn [b v] (push-write! b :int v))
          wf (fn [b v] (push-write! b :float v))
          ws (fn [b v] (push-write! b :str v))
          wbool (fn [b v] (push-write! b :bool v))]
      (codec/write-value* buf 12 wb wi wf ws wbool)
      (codec/write-value* buf 1.5 wb wi wf ws wbool)
      (codec/write-value* buf "ok" wb wi wf ws wbool)
      (codec/write-value* buf true wb wi wf ws wbool)
      (codec/write-value* buf nil wb wi wf ws wbool)
      (codec/write-value* buf {:k 1} wb wi wf ws wbool)
      (is (= [[:byte 0] [:int 12]
              [:byte 1] [:float 1.5]
              [:byte 2] [:str "ok"]
              [:byte 3] [:bool true]
              [:byte 4]
              [:byte 5] [:str "{:k 1}"]]
             (:writes @buf))))))

(deftest read-value-contract-test
  (testing "read-value* decodes tagged values and unknown type returns nil"
    (let [buf (-> (new-buffer) (with-reads [0 8 1 2.5 2 "str" 3 true 4 5 "{:x 1}" 9]))
          rb (fn [b] (pop-read! b))
          ri (fn [b] (pop-read! b))
          rf (fn [b] (pop-read! b))
          rs (fn [b] (pop-read! b))
          rbool (fn [b] (pop-read! b))]
      (is (= 8 (codec/read-value* buf rb ri rf rs rbool)))
      (is (= 2.5 (codec/read-value* buf rb ri rf rs rbool)))
      (is (= "str" (codec/read-value* buf rb ri rf rs rbool)))
      (is (= true (codec/read-value* buf rb ri rf rs rbool)))
      (is (= nil (codec/read-value* buf rb ri rf rs rbool)))
      (is (= "{:x 1}" (codec/read-value* buf rb ri rf rs rbool)))
      (is (= nil (codec/read-value* buf rb ri rf rs rbool))))))

(deftest data-map-roundtrip-test
  (testing "write-data-map* and read-data-map* preserve keyword keys"
    (let [payload {:count 2 :title "abc"}
          written (atom [])
          write-int (fn [_ v] (swap! written conj [:int v]))
          write-str (fn [_ v] (swap! written conj [:str v]))
          write-val (fn [_ v] (swap! written conj [:val v]))]
      (codec/write-data-map* nil payload write-int write-str write-val)
      (is (= 5 (count @written)))
      (is (= [:int 2] (first @written))))
    (let [reads (atom [2 "count" 2 "title" "abc"])
          read-int (fn [_] (let [v (first @reads)] (swap! reads subvec 1) v))
          read-str (fn [_] (let [v (first @reads)] (swap! reads subvec 1) v))
          read-val (fn [_] (let [v (first @reads)] (swap! reads subvec 1) v))]
      (is (= {:count 2 :title "abc"}
             (codec/read-data-map* nil read-int read-str read-val))))))
