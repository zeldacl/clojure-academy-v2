(ns cn.li.ac.test.support.wireless-stubs
  (:require [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.world :as pworld])
  (:import [cn.li.acapi.wireless IWirelessGenerator IWirelessMatrix IWirelessNode IWirelessReceiver]))

(deftype TestPos [^long x ^long y ^long z]
  pos/IBlockPos
  (pos-x [_] x)
  (pos-y [_] y)
  (pos-z [_] z))

(defn- nbt-compound
  []
  (let [data (atom {})]
    (reify
      nbt/INBTCompound
      (nbt-set-int! [this k v] (swap! data assoc k (int v)) this)
      (nbt-get-int [_ k] (int (get @data k 0)))
      (nbt-set-long! [this k v] (swap! data assoc k (long v)) this)
      (nbt-get-long [_ k] (long (get @data k 0)))
      (nbt-set-float! [this k v] (swap! data assoc k (float v)) this)
      (nbt-get-float [_ k] (float (get @data k 0.0)))
      (nbt-set-double! [this k v] (swap! data assoc k (double v)) this)
      (nbt-get-double [_ k] (double (get @data k 0.0)))
      (nbt-set-string! [this k v] (swap! data assoc k (str v)) this)
      (nbt-get-string [_ k] (str (get @data k "")))
      (nbt-set-boolean! [this k v] (swap! data assoc k (boolean v)) this)
      (nbt-get-boolean [_ k] (boolean (get @data k false)))
      (nbt-set-tag! [this k tag] (swap! data assoc k tag) this)
      (nbt-get-tag [_ k] (get @data k))
      (nbt-get-compound [_ k]
        (let [v (get @data k)]
          (when (satisfies? nbt/INBTCompound v) v)))
      (nbt-get-list [_ k]
        (let [v (get @data k)]
          (when (satisfies? nbt/INBTList v) v)))
      (nbt-has-key? [_ k] (contains? @data k)))))

(defn- nbt-list
  []
  (let [items (atom [])]
    (reify
      nbt/INBTList
      (nbt-append! [this el] (swap! items conj el) this)
      (nbt-list-size [_] (count @items))
      (nbt-list-get [_ i] (get @items i))
      (nbt-list-get-compound [_ i]
        (let [v (get @items i)]
          (when (satisfies? nbt/INBTCompound v) v))))))

(defn fake-matrix
  ([] (fake-matrix {}))
  ([{:keys [capacity bandwidth matrix-range]
     :or {capacity 64 bandwidth 128.0 matrix-range 16.0}}]
   (reify IWirelessMatrix
     (getMatrixCapacity [_] capacity)
     (getMatrixBandwidth [_] bandwidth)
     (getMatrixRange [_] matrix-range)
     (getSsid [_] "ssid-a")
     (getPassword [_] "pw")
     (getPlacerName [_] "tester"))))

(defn mutable-node
  [{:keys [energy max-energy bandwidth capacity range password]
    :or {energy 0.0 max-energy 1000.0 bandwidth 100.0 capacity 8 range 10.0 password "pw"}}]
  (let [e (atom energy)]
    (reify IWirelessNode
      (getEnergy [_] @e)
      (setEnergy [_ v] (reset! e v))
      (getMaxEnergy [_] max-energy)
      (getBandwidth [_] bandwidth)
      (getCapacity [_] capacity)
      (getRange [_] range)
      (getNodeName [_] "node-test")
      (getPassword [_] password)
      (getBlockPos [_] nil))))

(defn fake-node [password]
  (mutable-node {:password password}))

(defn generator-stub
  [{:keys [bandwidth provided-fn]
    :or {bandwidth 100.0
         provided-fn (fn [_required] 0.0)}}]
  (reify IWirelessGenerator
    (getEnergy [_] 0.0)
    (setEnergy [_ _] nil)
    (getProvidedEnergy [_ required] (double (provided-fn required)))
    (getGeneratorBandwidth [_] bandwidth)))

(defn receiver-stub
  [{:keys [bandwidth required leftover-fn]
    :or {bandwidth 100.0 required 50.0 leftover-fn (constantly 0.0)}}]
  (reify IWirelessReceiver
    (getRequiredEnergy [_] (double required))
    (injectEnergy [_ give] (double (leftover-fn give)))
    (pullEnergy [_ _] 0.0)
    (getReceiverBandwidth [_] (double bandwidth))))

(defn fake-generator []
  (generator-stub {}))

(defn fake-receiver []
  (receiver-stub {:required 0.0}))

(defn with-tile-world
  "Bind platform world fns so tile entities resolve from `tiles-atom` keyed by [x y z] (ints)."
  [tiles-atom f]
  (binding [pos/*position-factory* (fn [x y z] (TestPos. (long x) (long y) (long z)))
            nbt/*nbt-factory* {:create-compound nbt-compound
                              :create-list nbt-list}
            ;; use map contains? for our stub compound
            nbt/*nbt-has-key-fn* (fn [compound k] (nbt/nbt-has-key? compound k))
            pworld/*world-is-chunk-loaded-fn* (fn [_ _ _] true)
            pworld/*world-get-tile-entity-fn*
            (fn [_ pos]
              (let [x (pos/position-get-x pos)
                    y (pos/position-get-y pos)
                    z (pos/position-get-z pos)]
                (get @tiles-atom [x y z])))]
    (f)))
