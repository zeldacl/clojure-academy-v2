(ns cn.li.mcmod.platform.nbt
  "NBT operations via Framework function map — pure relay layer, no MC dependencies.

   All MC interop (CompoundTag/ListTag) is installed by mc-1.20.1/installer_core.clj.
   Content code calls wrapper functions; they lookup from [:platform :nbt-ops]."
  (:require [cn.li.mcmod.framework :as fw]))

;; Contract keys
(def nbt-compound-keys #{:nbt-set-int! :nbt-get-int :nbt-set-string! :nbt-get-string
                          :nbt-set-boolean! :nbt-get-boolean :nbt-set-double! :nbt-get-double
                          :nbt-set-tag! :nbt-get-tag :nbt-get-compound :nbt-get-list
                          :nbt-has-key? :nbt-set-float! :nbt-get-float
                          :nbt-set-long! :nbt-get-long})
(def nbt-list-keys #{:nbt-append! :nbt-list-size :nbt-list-get :nbt-list-get-compound})
(def nbt-factory-keys #{:create-compound :create-list})

;; Installation
(defn install-nbt-ops!
  "Install NBT operations map. Keys match nbt-compound-keys + nbt-list-keys + nbt-factory-keys."
  [ops-map _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :nbt-ops] ops-map)) nil)

(defn install-nbt-has-key-fn!
  "Install a single has-key? override function at [:platform :nbt-ops :nbt-has-key?]."
  [f _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :nbt-ops :nbt-has-key?] f)) nil)

;; Queries
(defn nbt-ops-available? [] (boolean (get-in @(fw/fw-atom) [:platform :nbt-ops])))
(defn current-ops [] (get-in @(fw/fw-atom) [:platform :nbt-ops]))

;; Helper
(defn- call [k & args]
  (when-let [f (get (current-ops) k)]
    (apply f args)))

;; CompoundTag wrappers
(defn nbt-set-int!      [c k v] (call :nbt-set-int! c k v))
(defn nbt-get-int       [c k]   (call :nbt-get-int c k))
(defn nbt-set-string!   [c k v] (call :nbt-set-string! c k v))
(defn nbt-get-string    [c k]   (call :nbt-get-string c k))
(defn nbt-set-boolean!  [c k v] (call :nbt-set-boolean! c k v))
(defn nbt-get-boolean   [c k]   (call :nbt-get-boolean c k))
(defn nbt-set-double!   [c k v] (call :nbt-set-double! c k v))
(defn nbt-get-double    [c k]   (call :nbt-get-double c k))
(defn nbt-set-tag!      [c k tag] (call :nbt-set-tag! c k tag))
(defn nbt-get-tag       [c k]   (call :nbt-get-tag c k))
(defn nbt-get-compound  [c k]   (call :nbt-get-compound c k))
(defn nbt-get-list      [c k]   (call :nbt-get-list c k))
(defn nbt-has-key?      [c k]   (call :nbt-has-key? c k))
(defn nbt-set-float!    [c k v] (call :nbt-set-float! c k v))
(defn nbt-get-float     [c k]   (call :nbt-get-float c k))
(defn nbt-set-long!     [c k v] (call :nbt-set-long! c k v))
(defn nbt-get-long      [c k]   (call :nbt-get-long c k))

;; ListTag wrappers
(defn nbt-append!          [lst el] (call :nbt-append! lst el))
(defn nbt-list-size        [lst]    (call :nbt-list-size lst))
(defn nbt-list-get         [lst i]  (call :nbt-list-get lst i))
(defn nbt-list-get-compound [lst i] (call :nbt-list-get-compound lst i))

;; Factory wrappers
(defn create-nbt-compound []
  (if-let [f (get (current-ops) :create-compound)]
    (f)
    (throw (ex-info "NBT ops not installed" {:key :create-compound}))))
(defn create-nbt-list []
  (if-let [f (get (current-ops) :create-list)]
    (f)
    (throw (ex-info "NBT ops not installed" {:key :create-list}))))

;; Convenience
(defn nbt-has-key-safe? [compound key]
  (try (boolean (nbt-has-key? compound key)) (catch Throwable _ false)))
(defn nbt-compound-to-map [compound]
  (let [result (java.util.HashMap.)]
    (if-let [f (get (current-ops) :nbt-get-all-keys)]
      (doseq [k (f compound)] (.put result k (nbt-get-tag compound k)))
      ;; Without :nbt-get-all-keys installed, can't enumerate keys from pure relay layer
      ;; (MC class .keySet would require import not available in mcmod)
      )
    (into {} result)))
(defn factory-initialized? []
  (boolean (and (get (current-ops) :create-compound) (get (current-ops) :create-list))))
