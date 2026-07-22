(ns cn.li.mcmod.platform.nbt
  "NBT operations via Framework function map — pure relay layer, no MC dependencies.

   All MC interop (CompoundTag/ListTag) is installed by the selected Minecraft component.
   Content code calls wrapper functions; they lookup from [:platform :nbt-ops]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

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
  (if-let [fw-atom (fw/fw-atom)]
    (let [required (into nbt-compound-keys (into nbt-list-keys nbt-factory-keys))
          missing (seq (remove (set (keys ops-map)) required))]
      (swap! fw-atom assoc-in [:platform :nbt-ops] ops-map)
      (when missing
        (log/error "NBT ops MISSING required keys:" (pr-str missing))))
    (log/error "NBT ops install FAILED: Framework atom nil")))

(defn install-nbt-has-key-fn!
  "Install a single has-key? override function at [:platform :nbt-ops :nbt-has-key?]."
  [f _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :nbt-ops :nbt-has-key?] f)) nil)

;; Queries
(defn available? [] (boolean (get-in @(fw/fw-atom) [:platform :nbt-ops])))
(defn current-ops [] (get-in @(fw/fw-atom) [:platform :nbt-ops]))

;; Helper
(defn- call [k & args]
  (when-let [f (get (current-ops) k)]
    (apply f args)))

;; CompoundTag access API
(defn set-int!      [c k v]   (call :nbt-set-int! c k v))
(defn get-int       [c k]     (call :nbt-get-int c k))
(defn set-string!   [c k v]   (call :nbt-set-string! c k v))
(defn get-string    [c k]     (call :nbt-get-string c k))
(defn set-boolean!  [c k v]   (call :nbt-set-boolean! c k v))
(defn get-boolean   [c k]     (call :nbt-get-boolean c k))
(defn set-double!   [c k v]   (call :nbt-set-double! c k v))
(defn get-double    [c k]     (call :nbt-get-double c k))
(defn set-tag!      [c k tag] (call :nbt-set-tag! c k tag))
(defn get-tag       [c k]     (call :nbt-get-tag c k))
(defn get-compound  [c k]     (call :nbt-get-compound c k))
(defn get-list      [c k]     (call :nbt-get-list c k))
(defn has-key?      [c k]     (call :nbt-has-key? c k))
(defn set-float!    [c k v]   (call :nbt-set-float! c k v))
(defn get-float     [c k]     (call :nbt-get-float c k))
(defn set-long!     [c k v]   (call :nbt-set-long! c k v))
(defn get-long      [c k]     (call :nbt-get-long c k))

;; ListTag access API
(defn append!       [lst el] (call :nbt-append! lst el))
(defn list-size     [lst]    (call :nbt-list-size lst))
(defn list-get      [lst i]  (call :nbt-list-get lst i))
(defn list-compound [lst i]  (call :nbt-list-get-compound lst i))

;; Factory access API
(defn create-compound []
  (if-let [f (get (current-ops) :create-compound)]
    (f)
    (throw (ex-info "NBT ops not installed" {:key :create-compound}))))
(defn create-list []
  (if-let [f (get (current-ops) :create-list)]
    (f)
    (throw (ex-info "NBT ops not installed" {:key :create-list}))))

;; Convenience
(defn has-key-safe? [compound key]
  (try (boolean (has-key? compound key)) (catch Throwable _ false)))
(defn compound->map [compound]
  (let [result (java.util.HashMap.)]
    (if-let [f (get (current-ops) :nbt-get-all-keys)]
      (doseq [k (f compound)] (.put result k (get-tag compound k)))
      ;; Without :nbt-get-all-keys installed, can't enumerate keys from pure relay layer
      ;; (MC class .keySet would require import not available in mcmod)
      )
    (into {} result)))
(defn factory-initialized? []
  (boolean (and (get (current-ops) :create-compound) (get (current-ops) :create-list))))
