(ns my-mod.block.node-schema
  "Wireless node type specifications and state schema.

  Extracted into a standalone namespace so that my-mod.block.wireless-node
  (content definition) and my-mod.block.role-impls (Java-interface bridge)
  can both depend on it without introducing a circular namespace dependency.

  This is the *single point of definition* for all node state fields.
  Adding, removing, or renaming a field requires only editing
  node-state-schema below; NBT serialisation, GUI sync payloads, and
  BlockState update logic are all derived automatically via
  my-mod.block.state-schema."
  (:require [my-mod.block.state-schema :as schema]
            [my-mod.platform.nbt :as nbt]
            [my-mod.platform.item :as item]))

;; ============================================================================
;; Node type specifications
;; Single source of truth for per-tier capability values.
;; ============================================================================

(def node-types
  {:basic    {:max-energy  15000 :bandwidth 150 :range  9 :capacity  5}
   :standard {:max-energy  50000 :bandwidth 300 :range 12 :capacity 10}
   :advanced {:max-energy 200000 :bandwidth 900 :range 19 :capacity 20}})

(defn node-max-energy
  "Derive the max-energy for a state map from its :node-type."
  [state]
  (get-in node-types [(keyword (:node-type state :basic)) :max-energy] 15000))

;; ============================================================================
;; Inventory serialisation helpers
;; Used as :load-fn / :save-fn overrides in node-state-schema.
;; Kept in ac because they describe content state, while protocol calls remain platform-neutral.
;; ============================================================================

(defn- load-inventory
  "Deserialise a ListTag of ItemStack compounds into a [slot0 slot1] vector."
  [tag nbt-key default]
  (if (nbt/nbt-has-key? tag nbt-key)
    (let [inv-tag (nbt/nbt-get-list tag nbt-key)
          size    (nbt/nbt-list-size inv-tag)]
      (reduce
        (fn [v i]
          (let [st   (nbt/nbt-list-get-compound inv-tag i)
                slot (nbt/nbt-get-int st "Slot")
                item (item/create-item-from-nbt st)]
            (if (and (>= slot 0) (< slot (count v)))
              (assoc v slot (when-not (item/item-is-empty? item) item))
              v)))
        default
        (range size)))
    default))

(defn- save-inventory
  "Serialise a [slot0 slot1] vector into a ListTag and attach it to tag."
  [state tag nbt-key]
  (let [inv      (get state :inventory [nil nil])
        inv-list (nbt/create-nbt-list)]
    (doseq [slot (range (count inv))]
      (when-let [item (nth inv slot nil)]
        (let [st (nbt/create-nbt-compound)]
          (nbt/nbt-set-int! st "Slot" slot)
          (item/item-save-to-nbt item st)
          (nbt/nbt-append! inv-list st))))
    (nbt/nbt-set-tag! tag nbt-key inv-list)))

;; ============================================================================
;; Node state schema  ── the single point of definition
;;
;; To add a field:   append one map to this vector.
;; To rename a field: change :key (Clojure side) and/or :nbt-key (NBT side).
;; To remove a field: delete its map entry.
;;
;; Downstream effects that update automatically:
;;   - node-default-state        (schema->default-state)
;;   - NBT load fn               (schema->load-fn)
;;   - NBT save fn               (schema->save-fn)
;;   - GUI sync payload          (schema->sync-payload)
;;   - BlockState property sync  (schema->block-state-updater)
;;   - role-impls default values (schema/get-field)
;; ============================================================================

(def node-state-schema
  [;; ── identity ────────────────────────────────────────────────────────────
   {:key :node-type     :nbt-key "NodeType"  :type :keyword  :default :basic
    :persist? true  :gui-sync? true}

   {:key :node-name     :nbt-key "NodeName"  :type :string   :default "Unnamed"
    :persist? true  :gui-sync? true}

   {:key :password      :nbt-key "Password"  :type :string   :default ""
    :persist? true  :gui-sync? true}

   {:key :placer-name   :nbt-key "Placer"    :type :string   :default ""
    :persist? true  :gui-sync? true}

   ;; ── energy ──────────────────────────────────────────────────────────────
   ;; :block-state-xf maps stored energy → discrete 0-4 BlockState level.
   {:key :energy        :nbt-key "Energy"    :type :double   :default 0.0
    :persist? true  :gui-sync? true
    :block-state-prop "energy"
    :block-state-xf   (fn [e s]
                        (let [max-e (double (node-max-energy s))]
                          (min 4 (int (Math/round (* 4.0 (/ (double e)
                                                            (max 1.0 max-e))))))))}

   ;; ── connection status ────────────────────────────────────────────────────
   {:key :enabled       :nbt-key "Enabled"   :type :boolean  :default false
    :persist? true  :gui-sync? true
    :block-state-prop "connected"}

   ;; ── ephemeral charging flags (not persisted) ─────────────────────────────
   {:key :charging-in   :nbt-key nil         :type :boolean  :default false
    :persist? false :gui-sync? true}

   {:key :charging-out  :nbt-key nil         :type :boolean  :default false
    :persist? false :gui-sync? true}

   ;; ── tick counter (not persisted, not synced) ─────────────────────────────
   {:key :update-ticker :nbt-key nil         :type :int      :default 0
    :persist? false :gui-sync? false}

   ;; ── inventory (custom load/save for ItemStack handling) ──────────────────
   {:key :inventory     :nbt-key "NodeInventory" :type :inventory :default [nil nil]
    :persist? true  :gui-sync? false
    :load-fn load-inventory
    :save-fn save-inventory}])

;; ============================================================================
;; Pre-computed defaults (no duplication with schema)
;; ============================================================================

(def node-default-state
  (schema/schema->default-state node-state-schema))
