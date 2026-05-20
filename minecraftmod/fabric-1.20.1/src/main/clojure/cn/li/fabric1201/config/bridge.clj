(ns cn.li.fabric1201.config.bridge
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson Gson GsonBuilder]
           [java.io File]
           [net.fabricmc.loader.api FabricLoader]))

(defonce gson
  (delay (-> (GsonBuilder.)
             (.setPrettyPrinting)
             (.create))))

(defn- domain->file-name
  [domain extension]
  (let [ns-part (namespace domain)
        name-part (name domain)]
    (str ns-part "-" name-part extension)))

(defn- path-segments
  [descriptor]
  (str/split (:path descriptor) #"\."))

(defn- nested-config-map
  [descriptors values]
  (reduce (fn [acc descriptor]
            (assoc-in acc (path-segments descriptor) (get values (:key descriptor) (:default descriptor))))
          {}
          descriptors))

(defn- read-json-file
  [^File file]
  (with-open [reader (io/reader file)]
    (let [^Gson gson-instance @gson]
      (.fromJson gson-instance reader java.util.Map))))

(defn- apply-numeric-bounds
  [descriptor value]
  (let [value (double value)
        value (if-let [min-val (:min descriptor)]
                (max (double min-val) value)
                value)
        value (if-let [max-val (:max descriptor)]
                (min (double max-val) value)
                value)]
    value))

(defn- coerce-value
  [descriptor raw-value]
  (try
    (case (:type descriptor)
        :int (let [value (cond
            (number? raw-value) (int raw-value)
            (string? raw-value) (Integer/parseInt raw-value)
            :else (:default descriptor))]
          (int (apply-numeric-bounds descriptor value)))
        :double (let [value (cond
          (number? raw-value) (double raw-value)
          (string? raw-value) (Double/parseDouble raw-value)
          :else (:default descriptor))]
        (double (apply-numeric-bounds descriptor value)))
      :boolean (cond
                 (instance? Boolean raw-value) raw-value
                 (string? raw-value) (Boolean/parseBoolean raw-value)
                 :else (:default descriptor))
      :string (if (nil? raw-value) (:default descriptor) (str raw-value))
      :string-list (if (instance? java.util.List raw-value)
                     (vec (map str raw-value))
                     (:default descriptor))
      :int-list (if (instance? java.util.List raw-value)
                  (vec (map #(coerce-value (assoc descriptor :type :int :default 0) %) raw-value))
                  (:default descriptor))
      :double-list (if (instance? java.util.List raw-value)
                     (vec (map #(coerce-value (assoc descriptor :type :double :default 0.0) %) raw-value))
                     (:default descriptor))
      (:default descriptor))
    (catch Exception _
      (:default descriptor))))

(defn- resolve-domain-values
  [descriptors parsed-tree]
  (into {}
        (for [descriptor descriptors
              :let [raw-value (when parsed-tree
                                (get-in parsed-tree (path-segments descriptor)))]]
          [(:key descriptor)
           (if (nil? raw-value)
             (:default descriptor)
             (coerce-value descriptor raw-value))])))

(defn- write-domain-file!
  [^File file descriptors values]
  (let [^File parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    (with-open [writer (io/writer file)]
      (let [^Gson gson-instance @gson]
        (.toJson gson-instance (nested-config-map descriptors values) writer)))))

(defn- load-domain!
  [domain]
  (let [descriptors (config-reg/get-config-descriptors domain)
        file-name (domain->file-name domain ".json")
        config-dir (-> (FabricLoader/getInstance) (.getConfigDir) (.toFile))
        file (io/file config-dir file-name)
        parsed-tree (when (.exists file)
                      (try
                        (read-json-file file)
                        (catch Exception e
                          (log/warn "Failed to read Fabric config file" (.getPath file) ":" (ex-message e))
                          nil)))
        values (resolve-domain-values descriptors parsed-tree)]
    (config-reg/set-config-values! domain values)
    (write-domain-file! file descriptors values)
    (log/info "Loaded Fabric config domain" domain "from" (.getPath file))))

(defn load-all! []
  (doseq [domain (config-reg/get-all-config-domains)]
    (when (seq (config-reg/get-config-descriptors domain))
      (load-domain! domain)))
  nil)