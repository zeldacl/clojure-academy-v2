(ns my-mod.block.matrix-schema
  "Wireless Matrix state schema.

  This is the *single point of definition* for all matrix state fields.
  Adding, removing, or renaming a field requires only editing
  matrix-state-schema below; NBT serialisation and GUI sync payloads are
  derived automatically via my-mod.block.state-schema.

  Note: :is-working, :capacity, :bandwidth, and :range are *derived* values
  computed from :plate-count / :core-level at call sites; they are not stored
  fields and therefore not present in this schema."
  (:require [my-mod.block.state-schema :as schema]))

;; ============================================================================
;; Inventory serialisation helpers
;; Used as :load-fn / :save-fn overrides in matrix-state-schema.
;; ============================================================================

(defn- load-inventory
  "Deserialise a ListTag of ItemStack compounds into a [s0 s1 s2 s3] vector."
  [tag nbt-key default]
  (if (.contains tag nbt-key)
    (let [inv-tag (.getList tag nbt-key 10)
          size    (.size inv-tag)]
      (reduce
        (fn [v i]
          (let [slot-tag (.getCompound inv-tag i)
                slot     (.getInt slot-tag "Slot")
                item     (net.minecraft.world.item.ItemStack/of slot-tag)]
            (if (and (>= slot 0) (< slot (count v)))
              (assoc v slot (when-not (.isEmpty item) item))
              v)))
        default
        (range size)))
    default))

(defn- save-inventory
  "Serialise a [s0 s1 s2 s3] vector into a ListTag and attach to tag."
  [state tag nbt-key]
  (let [inv      (get state :inventory [nil nil nil nil])
        inv-list (net.minecraft.nbt.ListTag.)]
    (doseq [slot (range (count inv))]
      (when-let [item (nth inv slot nil)]
        (let [slot-tag (net.minecraft.nbt.CompoundTag.)]
          (.putInt slot-tag "Slot" slot)
          (.save item slot-tag)
          (.add inv-list slot-tag))))
    (.put tag nbt-key inv-list)))

;; ============================================================================
;; Matrix state schema  ── the single point of definition
;;
;; To add a field:   append one map to this vector.
;; To rename a field: change :key (Clojure side) and/or :nbt-key (NBT side).
;; To remove a field: delete its map entry.
;;
;; Downstream effects that update automatically:
;;   - matrix-default-state     (schema->default-state)
;;   - NBT load fn              (schema->load-fn)
;;   - NBT save fn              (schema->save-fn)
;;   - GUI sync payload         (schema->sync-payload)
;;   - role-impls default values (schema/get-field)
;; ============================================================================

(def matrix-state-schema
  [;; ── identity ────────────────────────────────────────────────────────────
   {:key :placer-name   :nbt-key "Placer"      :type :string   :default ""
    :persist? true  :gui-sync? true}

   ;; ── installed components ─────────────────────────────────────────────────
   {:key :plate-count   :nbt-key "PlateCount"  :type :int      :default 0
    :persist? true  :gui-sync? true}

   {:key :core-level    :nbt-key "CoreLevel"   :type :int      :default 0
    :persist? true  :gui-sync? true}

   ;; ── structural ───────────────────────────────────────────────────────────
   {:key :direction     :nbt-key "Direction"   :type :keyword  :default :north
    :persist? true  :gui-sync? false}

   {:key :sub-id        :nbt-key "SubId"       :type :int      :default 0
    :persist? true  :gui-sync? false}

   ;; ── ephemeral tick counter (not persisted, not synced) ───────────────────
   {:key :update-ticker :nbt-key nil           :type :int      :default 0
    :persist? false :gui-sync? false}

   ;; ── inventory (custom load/save for ItemStack handling) ──────────────────
   {:key :inventory     :nbt-key "Inventory"   :type :inventory :default [nil nil nil nil]
    :persist? true  :gui-sync? false
    :load-fn load-inventory
    :save-fn save-inventory}])

;; ============================================================================
;; Pre-computed defaults (no duplication with schema)
;; ============================================================================

(def matrix-default-state
  (schema/schema->default-state matrix-state-schema))
