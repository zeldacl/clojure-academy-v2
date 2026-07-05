(ns cn.li.ac.test.support.nbt
  (:require [cn.li.mcmod.platform.nbt :as nbt]))

(def ^:private compound-marker ::atom-compound)
(def ^:private list-marker ::atom-list)
(def ^:private ops-installed? (atom false))

(defn- nk [k] (if (keyword? k) (name k) (str k)))

(defn- compound? [c] (= compound-marker (:type c)))
(defn- nbt-list-value? [c] (= list-marker (:type c)))
(defn- compound-data [c] (:data c))
(defn- list-data [c] (:data c))

(defn- new-compound [] {:type compound-marker :data (atom {})})
(defn- new-list [] {:type list-marker :data (atom [])})

(defn install-test-nbt-ops!
  []
  (when-not @ops-installed?
    (reset! ops-installed? true)
    (nbt/install-nbt-ops!
      {:nbt-set-int! (fn [c k v]
                       (when (compound? c)
                         (swap! (compound-data c) assoc (nk k) (int v)))
                       c)
       :nbt-get-int (fn [c k]
                      (when (compound? c)
                        (int (get @(compound-data c) (nk k) 0))))
       :nbt-set-string! (fn [c k v]
                          (when (compound? c)
                            (swap! (compound-data c) assoc (nk k) (str v)))
                          c)
       :nbt-get-string (fn [c k]
                         (when (compound? c)
                           (str (get @(compound-data c) (nk k) ""))))
       :nbt-set-boolean! (fn [c k v]
                           (when (compound? c)
                             (swap! (compound-data c) assoc (nk k) (boolean v)))
                           c)
       :nbt-get-boolean (fn [c k]
                          (when (compound? c)
                            (boolean (get @(compound-data c) (nk k) false))))
       :nbt-set-double! (fn [c k v]
                          (when (compound? c)
                            (swap! (compound-data c) assoc (nk k) (double v)))
                          c)
       :nbt-get-double (fn [c k]
                         (when (compound? c)
                           (double (get @(compound-data c) (nk k) 0.0))))
       :nbt-set-float! (fn [c k v]
                         (when (compound? c)
                           (swap! (compound-data c) assoc (nk k) (float v)))
                         c)
       :nbt-get-float (fn [c k]
                        (when (compound? c)
                          (float (get @(compound-data c) (nk k) 0.0))))
       :nbt-set-long! (fn [c k v]
                        (when (compound? c)
                          (swap! (compound-data c) assoc (nk k) (long v)))
                        c)
       :nbt-get-long (fn [c k]
                       (when (compound? c)
                         (long (get @(compound-data c) (nk k) 0))))
       :nbt-set-tag! (fn [c k v]
                       (when (compound? c)
                         (swap! (compound-data c) assoc (nk k) v))
                       c)
       :nbt-get-tag (fn [c k]
                      (when (compound? c)
                        (get @(compound-data c) (nk k))))
       :nbt-get-compound (fn [c k]
                           (when (compound? c)
                             (let [v (get @(compound-data c) (nk k))]
                               (when (compound? v) v))))
       :nbt-get-list (fn [c k]
                      (when (compound? c)
                        (let [v (get @(compound-data c) (nk k))]
                          (when (nbt-list-value? v) v))))
       :nbt-has-key? (fn [c k]
                       (when (compound? c)
                         (contains? @(compound-data c) (nk k))))
       :nbt-append! (fn [lst el]
                      (when (nbt-list-value? lst)
                        (swap! (list-data lst) conj el))
                      lst)
       :nbt-list-size (fn [lst]
                        (when (nbt-list-value? lst)
                          (count @(list-data lst))))
       :nbt-list-get (fn [lst i]
                       (when (nbt-list-value? lst)
                         (get @(list-data lst) i)))
       :nbt-list-get-compound (fn [lst i]
                                (when (nbt-list-value? lst)
                                  (let [v (get @(list-data lst) i)]
                                    (when (compound? v) v))))
       :create-compound new-compound
       :create-list new-list}
      "ac-test")))

(defn atom-compound
  []
  (install-test-nbt-ops!)
  (new-compound))

(defn atom-list
  []
  (install-test-nbt-ops!)
  (new-list))
