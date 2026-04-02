(ns cn.li.fabric1201.config.bridge
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.mcmod.config.registry :as config-reg]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson GsonBuilder]
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
  [file]
  (with-open [reader (io/reader file)]
    (.fromJson @gson reader java.util.Map)))

(defn- coerce-value
  [descriptor raw-value]
  (try
    (case (:type descriptor)
      :int (cond
             (number? raw-value) (int raw-value)
             (string? raw-value) (Integer/parseInt raw-value)
             :else (:default descriptor))
      :double (cond
                (number? raw-value) (double raw-value)
                (string? raw-value) (Double/parseDouble raw-value)
                :else (:default descriptor))
      :boolean (cond
                 (instance? Boolean raw-value) raw-value
                 (string? raw-value) (Boolean/parseBoolean raw-value)
                 :else (:default descriptor))
      :string (if (nil? raw-value) (:default descriptor) (str raw-value))
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
  [file descriptors values]
  (let [parent (.getParentFile file)]
    (when parent
      (.mkdirs parent))
    (with-open [writer (io/writer file)]
      (.toJson @gson (nested-config-map descriptors values) writer))))

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