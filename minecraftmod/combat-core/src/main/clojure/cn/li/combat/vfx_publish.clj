(ns cn.li.combat.vfx-publish
  "Pure combat-side projection of visual intents.")

(defn intent
  [{:keys [effect-id op instance-key params audience owner event-seq]}]
  (when-not (keyword? effect-id)
    (throw (ex-info "VFX intent requires a keyword effect id" {:effect-id effect-id})))
  (when-not (contains? #{:spawn :update :destroy :clear-owner} op)
    (throw (ex-info "unknown VFX intent operation" {:op op})))
  {:effect-id effect-id :op op :instance-key instance-key
   :params (or params {}) :audience audience :owner owner
   :event-seq (long (or event-seq 0))})

(defn intents [result]
  (mapv intent (:vfx-signals result)))

(defn without-intents [result]
  (dissoc result :vfx-signals))

(defn publish-combat-result!
  "Return the non-visual result envelope. VFX routing is owned by the
   ability-runtime composition layer; Combat Core performs no I/O."
  [_catalog result]
  (without-intents result))

(defn reset-for-test!
  "Retained only as a deterministic no-op for existing test fixtures; the
   production implementation has no process-global state to reset."
  []
  nil)

(defn broadcast-clear-owner!
  "Emit a clear-owner intent; actual recipient selection belongs to VFX."
  [owner]
  (intent {:effect-id :runtime/owner-clear :op :clear-owner :owner owner}))

(defn replay-persistent-signals!
  "Persistent replay is now a VFX runtime concern; Combat contributes no
   mutable replay table."
  [_catalog]
  nil)

(defn install-result-sink! [_sink] nil)
(defn install-vfx-typed-self-sink! [_sink] nil)
(defn install-vfx-typed-broadcast-sink! [_sink] nil)
