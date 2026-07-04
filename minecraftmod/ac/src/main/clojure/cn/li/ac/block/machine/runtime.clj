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
  "Single boundary for BE state writes, dirty flag, and optional blockstate projection."
  [be level pos old-state new-state & {:keys [blockstate-updater mark-changed?]
                                       :or {mark-changed? true}}]
  (when (not= new-state old-state)
    (platform-be/set-custom-state! be new-state)
    (when mark-changed?
      (try (platform-be/set-changed! be) (catch Exception _ nil)))
    (when (and blockstate-updater level pos)
      (blockstate-updater new-state level pos))))

(defn- resolve-tile-context
  [tile]
  (when tile
    (let [world (try (platform-be/be-get-world-safe tile) (catch Exception _ nil))
          block-pos (when world (try (pos/position-get-block-pos tile) (catch Exception _ nil)))]
      {:world world :pos block-pos :level world})))

(defn commit-transform!
  "Apply `transform` to current tile state and commit when changed.

  `transform` receives current state map and returns new state.
  Options match `commit-state!`; optional `:after-commit!` runs when state changes."
  [tile default-state transform & {:keys [blockstate-updater mark-changed? after-commit!]}]
  (when tile
    (let [{:keys [level pos]} (resolve-tile-context tile)
          old (state-or-default tile default-state)
          new-state (transform old)
          ctx {:level level :pos pos :be tile}]
      (commit-state! tile level pos old new-state
                     :blockstate-updater blockstate-updater
                     :mark-changed? mark-changed?)
      (when (and after-commit! (not= new-state old))
        (after-commit! tile level pos old new-state ctx)))))

(defn commit-from-tile!
  "Commit an explicit new state for a tile (old state read from tile)."
  [tile default-state new-state & {:keys [blockstate-updater mark-changed? after-commit!]}]
  (when tile
    (let [{:keys [level pos]} (resolve-tile-context tile)
          old (state-or-default tile default-state)
          ctx {:level level :pos pos :be tile}]
      (commit-state! tile level pos old new-state
                     :blockstate-updater blockstate-updater
                     :mark-changed? mark-changed?)
      (when (and after-commit! (not= new-state old))
        (after-commit! tile level pos old new-state ctx)))))

(defn server-side?
  [level]
  (and level (not (world/world-is-client-side* level))))

(defn make-tick-fn
  "Wrap a pure (fn [state ctx] -> state') step with server guard and commit-state!.

  Options:
  - :initial-state (fn [be ctx] -> state) optional; defaults to state-or-default + :default-state
  - :after-commit! (fn [be level pos old-state new-state ctx]) optional side effects after commit
  - :mark-changed? may be true, false, or (fn [old-state new-state] -> bool)."
  [{:keys [default-state initial-state tick-state blockstate-updater after-commit! mark-changed?]
    :or {mark-changed? true}}]
  (fn [level pos block-state be]
    (when (server-side? level)
      (let [ctx {:level level :pos pos :block-state block-state :be be}
            state0 (if initial-state
                     (initial-state be ctx)
                     (state-or-default be default-state))
            state1 (tick-state state0 ctx)
            dirty? (cond
                     (fn? mark-changed?) (mark-changed? state0 state1)
                     :else (boolean mark-changed?))]
        (commit-state! be level pos state0 state1
                       :blockstate-updater blockstate-updater
                       :mark-changed? dirty?)
        (when (and after-commit! (not= state1 state0))
          (after-commit! be level pos state0 state1 ctx))))))

(defn make-open-gui-handler*
  "Like make-open-gui-handler but `can-open?` receives the full event ctx.

  Optional:
  - `:resolve-open-pos` (fn [ctx] -> pos) for multiblock controller cells
  - `:server-before-open!` (fn [ctx open-pos] -> truthy) runs on dedicated server before open;
    return false/nil to abort (e.g. occupancy check + state commit)"
  [gui-type can-open? & {:keys [resolve-open-pos server-before-open!]}]
  (fn [{:keys [player world pos sneaking] :as ctx}]
    (when (and player world pos (not sneaking) (can-open? ctx))
      (try
        (let [open-pos (if resolve-open-pos (resolve-open-pos ctx) pos)]
          (when open-pos
            (if (world/world-is-client-side* world)
              (gui-open/open-gui-by-type player gui-type world open-pos)
              (when (or (not server-before-open!) (server-before-open! ctx open-pos))
                (gui-open/open-gui-by-type player gui-type world open-pos)))))
        (catch Exception e
          (log/error "Failed to open GUI" gui-type ":" (ex-message e))
          nil)))))

(defn make-open-gui-handler
  "Build a block right-click handler that opens an AC GUI by business type keyword."
  [gui-type]
  (make-open-gui-handler* gui-type (constantly true)))

(defn inc-update-ticker
  "Common helper for machines that track :update-ticker."
  [state]
  (update state :update-ticker (fn [t] (inc (long (or t 0))))))
