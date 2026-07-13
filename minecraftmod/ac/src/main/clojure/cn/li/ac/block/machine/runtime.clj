(ns cn.li.ac.block.machine.runtime
  "Unified server-side block machine runtime: schema lifecycle, state commit, tick wrapper, GUI open."
  (:require [cn.li.ac.gui.open :as gui-open]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log]))

(defn schema-runtime
  "Build runtime bundle from a full or server schema vector."
  [schema & {:keys [server-only?]}]
  (let [server-schema (if server-only?
                        schema
                        (state-schema/filter-server-fields schema))
        blockstate-fields (filterv #(get % :block-state) server-schema)]
    {:schema schema
     :server-schema server-schema
     :default-state (state-schema/schema->default-state server-schema)
     :load-fn (state-schema/schema->load-fn server-schema)
     :save-fn (state-schema/schema->save-fn server-schema)
     :blockstate-fields blockstate-fields
     :block-state-properties (state-schema/extract-block-state-properties blockstate-fields)
     :blockstate-updater (when (seq blockstate-fields)
                           (state-schema/build-block-state-updater blockstate-fields))}))

(defn state-or-default
  [be default-state]
  (or (platform-be/get-custom-state be) default-state))

(defn commit-state!
  "Single boundary for BE state writes, dirty flag, client sync, and optional blockstate projection.

  `:mark-changed?` (default true) controls setChanged (NBT save dirty).
  `:sync-client?` (default false) controls an explicit client block-update packet.
  Most machines don't need this: visuals go through BlockState properties or GUI
  DataSlots. Declare true only when a TESR reads BE custom-state directly."
  [be level pos old-state new-state & {:keys [blockstate-updater mark-changed? sync-client?]
                                       :or {mark-changed? true sync-client? false}}]
  (when (not= new-state old-state)
    (platform-be/set-custom-state! be new-state)
    (when mark-changed?
      (try (platform-be/set-changed! be) (catch Exception _ nil)))
    (when sync-client?
      (try (platform-be/sync-to-client! be) (catch Exception _ nil)))
    (when (and blockstate-updater level pos)
      (blockstate-updater new-state level pos))))

(defn- resolve-tile-level-pos
  "Return [level pos] for a tile, or [nil nil] when unavailable."
  [tile]
  (when tile
    (let [level (try (platform-be/be-get-world-safe tile) (catch Exception _ nil))
          block-pos (when level (try (pos/position-get-block-pos tile) (catch Exception _ nil)))]
      [level block-pos])))

(defn- compute-dirty-flag
  [mark-changed? state0 state1]
  (if (fn? mark-changed?)
    (mark-changed? state0 state1)
    (boolean mark-changed?)))

(defn commit-transform!
  "Apply `transform` to current tile state and commit when changed.

  `transform` receives current state map and returns new state.
  Options match `commit-state!`; optional `:after-commit!` runs when state changes.

  NOTE: defaults for `:mark-changed?`/`:sync-client?` are resolved HERE (not left to
  `commit-state!`'s `:or`) — an omitted keyword here would otherwise pass an explicit
  `nil` through to `commit-state!`, which defeats its own `:or` default (a present key
  bound to nil is not the same as an absent key)."
  [tile default-state transform & {:keys [blockstate-updater mark-changed? sync-client? after-commit!]
                                   :or {mark-changed? true sync-client? false}}]
  (when tile
    (let [[level pos] (resolve-tile-level-pos tile)
          old (state-or-default tile default-state)
          new-state (transform old)]
      (commit-state! tile level pos old new-state
                     :blockstate-updater blockstate-updater
                     :mark-changed? mark-changed?
                     :sync-client? sync-client?)
      (when (and after-commit! (not= new-state old))
        (after-commit! tile level pos old new-state)))))

(defn commit-from-tile!
  "Commit an explicit new state for a tile (old state read from tile).
  See `commit-transform!` for why defaults are resolved here."
  [tile default-state new-state & {:keys [blockstate-updater mark-changed? sync-client? after-commit!]
                                   :or {mark-changed? true sync-client? false}}]
  (when tile
    (let [[level pos] (resolve-tile-level-pos tile)
          old (state-or-default tile default-state)]
      (commit-state! tile level pos old new-state
                     :blockstate-updater blockstate-updater
                     :mark-changed? mark-changed?
                     :sync-client? sync-client?)
      (when (and after-commit! (not= new-state old))
        (after-commit! tile level pos old new-state)))))

(defn server-side?
  [level]
  (and level (not (world/world-is-client-side* level))))

(defn make-tick-fn
  "Wrap a pure tick step with server guard and commit-state!.

  Options:
  - :tick-state (fn [state level pos block-state be] -> state')
  - :initial-state (fn [be level pos block-state] -> state) optional
  - :after-commit! (fn [be level pos old-state new-state] -> nil) optional
  - :mark-changed? may be true, false, or (fn [old-state new-state] -> bool).
  - :sync-client? may be true, false, or (fn [old-state new-state] -> bool); default false.
    Only declare true for machines whose TESR reads BE custom-state directly — most
    visuals go through BlockState properties or GUI DataSlots and need no BE packet."
  [{:keys [default-state initial-state tick-state blockstate-updater after-commit! mark-changed? sync-client?]
    :or {mark-changed? true sync-client? false}}]
  (fn [level pos block-state be]
    (when (server-side? level)
      (let [state0 (if initial-state
                     (initial-state be level pos block-state)
                     (state-or-default be default-state))
            state1 (tick-state state0 level pos block-state be)
            dirty? (compute-dirty-flag mark-changed? state0 state1)
            sync?  (compute-dirty-flag sync-client? state0 state1)]
        (commit-state! be level pos state0 state1
                       :blockstate-updater blockstate-updater
                       :mark-changed? dirty?
                       :sync-client? sync?)
        (when (and after-commit! (not= state1 state0))
          (after-commit! be level pos state0 state1))))))

(defn make-open-gui-handler*
  "Build a block right-click handler that opens an AC GUI.

  `can-open?` receives (player world pos sneaking item-stack).
  Optional:
  - `:resolve-open-pos` (fn [player world pos] -> pos)
  - `:server-before-open!` (fn [player world open-pos] -> truthy)"
  [gui-type can-open? & {:keys [resolve-open-pos server-before-open!]}]
  (fn [player world pos block-id & {:keys [sneaking item-stack]}]
    (when (and player world pos (not sneaking) (can-open? player world pos sneaking item-stack))
      (try
        (let [open-pos (if resolve-open-pos (resolve-open-pos player world pos) pos)]
          (when open-pos
            (if (world/world-is-client-side* world)
              (gui-open/open-gui-by-type player gui-type world open-pos)
              (when (or (not server-before-open!) (server-before-open! player world open-pos))
                (gui-open/open-gui-by-type player gui-type world open-pos)))))
        (catch Exception e
          (log/error "Failed to open GUI" gui-type ":" (ex-message e))
          nil)))))

(defn make-open-gui-handler
  "Build a block right-click handler that opens an AC GUI by business type keyword."
  [gui-type]
  (make-open-gui-handler* gui-type (fn [_ _ _ _ _] true)))

(defn inc-update-ticker
  "Common helper for machines that track :update-ticker."
  [state]
  (update state :update-ticker (fn [t] (inc (long (or t 0))))))

(defn changed-ignoring-ticker?
  "Dirty/sync predicate for machines using `inc-update-ticker`: a real change is
  anything besides :update-ticker differing. Use as :mark-changed? (and, when the
  TESR reads BE custom-state directly, also :sync-client?) so an idle machine whose
  only per-tick mutation is the bookkeeping ticker never marks NBT dirty or sends a
  client packet."
  [old-state new-state]
  (not= (dissoc old-state :update-ticker) (dissoc new-state :update-ticker)))
