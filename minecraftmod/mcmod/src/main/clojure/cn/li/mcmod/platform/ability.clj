(ns cn.li.mcmod.platform.ability
  "Platform-neutral ability protocols.

  These protocols define the data contracts between the platform (forge/fabric)
  and ac game logic. ac implements business rules; platform stores and serializes.

  Dependency direction: platform -> mcmod -> ac. This namespace must never import
  net.minecraft.* or reference ac namespaces.")

;; ============================================================================
;; Ability Data Protocol
;; ============================================================================

(defprotocol IPlayerAbilityData
  "Read/write ability data for a player. Keyed by player UUID string.
  Platform implements persistence; ac implements rules on top of returned maps."

  (ability-get-category [this uuid]
    "Returns category-id keyword or nil")

  (ability-set-category! [this uuid cat-id]
    "Sets category. Triggers persistence dirty-flag.")

  (ability-is-learned? [this uuid skill-id]
    "Returns true if skill-id (keyword) is in the learned set")

  (ability-learn-skill! [this uuid skill-id]
    "Mark skill-id as learned in the persistent store.")

  (ability-get-skill-exp [this uuid skill-id]
    "Returns float 0.0-1.0 for the given skill-id")

  (ability-set-skill-exp! [this uuid skill-id amount]
    "Set skill exp (clamped 0-1). Triggers persistence dirty-flag.")

  (ability-get-level [this uuid]
    "Returns integer 1-5")

  (ability-set-level! [this uuid level]
    "Set level (1-5). Triggers CP/overload recalc.")

  (ability-get-level-progress [this uuid]
    "Returns float ≥ 0.0 — accumulated exp toward next level")

  (ability-set-level-progress! [this uuid amount]
    "Overwrite level progress."))

;; ============================================================================
;; Resource Data Protocol (CP / Overload)
;; ============================================================================

(defprotocol IResourceData
  "Read/write CP and Overload state for a player."

  (res-get-cur-cp [this uuid])
  (res-get-max-cp [this uuid])
  (res-set-cur-cp! [this uuid v])
  (res-get-cur-overload [this uuid])
  (res-get-max-overload [this uuid])
  (res-set-cur-overload! [this uuid v])
  (res-is-overload-fine? [this uuid]
    "Returns true when overload < maxOverload and recovery is not active")
  (res-is-activated? [this uuid]
    "Returns true when ability activation is on")
  (res-set-activated! [this uuid v])
  (res-get-until-recover [this uuid]
    "Ticks remaining before CP recovery starts")
  (res-set-until-recover! [this uuid ticks])
  (res-get-interferences [this uuid]
    "Returns set of active interference source IDs")
  (res-add-interference! [this uuid src-id])
  (res-remove-interference! [this uuid src-id]))

;; ============================================================================
;; Cooldown Data Protocol
;; ============================================================================

(defprotocol ICooldownData
  "Per-player cooldown map keyed by [ctrl-id sub-id]."

  (cd-is-in-cooldown? [this uuid ctrl-id sub-id])
  (cd-set-cooldown! [this uuid ctrl-id sub-id ticks]
    "Set cooldown to max(existing, ticks).")
  (cd-get-remaining [this uuid ctrl-id sub-id]
    "Returns remaining ticks or 0")
  (cd-tick! [this uuid]
    "Decrement all cooldowns by 1, remove expired entries."))

;; ============================================================================
;; Preset Data Protocol
;; ============================================================================

(defprotocol IPresetData
  "Per-player preset/key-binding map."

  (preset-get-active [this uuid]
    "Returns active preset index 0-3")
  (preset-set-active! [this uuid idx])
  (preset-get-slot [this uuid preset-idx key-idx]
    "Returns [cat-id ctrl-id] pair or nil")
  (preset-set-slot! [this uuid preset-idx key-idx controllable]
    "Set a slot. controllable is [cat-id ctrl-id] or nil to clear.")
  (preset-get-all [this uuid]
    "Returns nested map {preset-idx {key-idx controllable}} for serialization"))

;; ============================================================================
;; Player Ability Hook Registry
;; ============================================================================

(def ^:dynamic *player-ability-store*
  "Bound by platform (forge) to a reified IPlayerAbilityData / IResourceData /
  ICooldownData / IPresetData implementation.
  nil until platform init runs."
  nil)

(defn player-ability-store
  "Return the bound player ability store, or throw."
  []
  (or *player-ability-store*
      (throw (ex-info "Player ability store not initialised by platform" {}))))

;; ============================================================================
;; Serialization Contract
;; ============================================================================

;; ac-layer produces pure maps; platform serializes them.
;; All maps must be edn-clean (no Java objects as values).

(defn ability-data->map
  "Serialize ability data for a player to a pure map."
  [uuid]
  (let [s (player-ability-store)]
    {:category-id    (ability-get-category s uuid)
     :level          (ability-get-level s uuid)
     :level-progress (ability-get-level-progress s uuid)}))

(defn resource-data->map
  [uuid]
  (let [s (player-ability-store)]
    {:cur-cp           (res-get-cur-cp s uuid)
     :max-cp           (res-get-max-cp s uuid)
     :cur-overload     (res-get-cur-overload s uuid)
     :max-overload     (res-get-max-overload s uuid)
     :activated        (res-is-activated? s uuid)
     :until-recover    (res-get-until-recover s uuid)}))
