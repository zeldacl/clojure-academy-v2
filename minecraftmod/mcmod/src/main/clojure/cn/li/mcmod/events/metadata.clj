(ns cn.li.mcmod.events.metadata
  "Event metadata system - maps blocks to their event handlers.
  
  This module provides a metadata-driven event handling system, ensuring
  platform code does not contain hardcoded game-specific block names or
  event logic. All event handlers are registered dynamically from game logic.
  
  Architecture:
  - Game logic (block definitions) registers event handlers here
  - Platform code queries this module to dispatch events
  - Adding new blocks with events requires zero platform code changes"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.util.log :as log]))

;; Event Handler Registry
;; ============================================================

(defonce block-event-handlers
  ^{
     :doc "Registry of block event handlers.
  Structure: {block-id -> {:on-right-click fn, :on-break fn, ...}}"}
  (atom {}))

;; Registration API
;; ============================================================

(defn register-block-event-handler!
  "Register an event handler for a specific block.
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
    event-type: Keyword - Event type (e.g., :on-right-click, :on-break)
    handler-fn: Function - Event handler function (event-data -> any)
  
  Example:
    (register-block-event-handler! \"demo-block\" :on-right-click
      (fn [event-data] (log/info \"Clicked!\")))"
  [block-id event-type handler-fn]
  (when handler-fn
    (swap! block-event-handlers assoc-in [block-id event-type] handler-fn)
    (log/info "Registered" event-type "handler for block:" block-id)))

(defn unregister-block-event-handler!
  "Unregister an event handler for a specific block.
  
  Args:
    block-id: String - DSL block identifier
    event-type: Keyword - Event type to unregister"
  [block-id event-type]
  (swap! block-event-handlers update block-id dissoc event-type)
  (log/info "Unregistered" event-type "handler for block:" block-id))

;; Query API
;; ============================================================

(defn get-block-event-handler
  "Get the event handler for a specific block and event type.
  
  Args:
    block-id: String - DSL block identifier
    event-type: Keyword - Event type (e.g., :on-right-click)
  
  Returns:
    Function - Event handler function, or nil if not registered"
  [block-id event-type]
  (get-in @block-event-handlers [block-id event-type]))

(defn has-event-handler?
  "Check if a block has a handler for a specific event type.
  
  Args:
    block-id: String - DSL block identifier
    event-type: Keyword - Event type
  
  Returns:
    Boolean - true if handler exists"
  [block-id event-type]
  (some? (get-block-event-handler block-id event-type)))

(defn get-all-blocks-with-event
  "Get all block IDs that have a handler for a specific event type.
  
  Args:
    event-type: Keyword - Event type (e.g., :on-right-click)
  
  Returns:
    Sequence of block ID strings"
  [event-type]
  (->> @block-event-handlers
       (filter (fn [[block-id handlers]]
                 (contains? handlers event-type)))
       (map first)))

;; Block Identification API
;; ============================================================

(defn identify-block-from-registry-name
  "Identify block ID from Minecraft registry name.

  First tries a simple snake_case -> kebab-case conversion. If that fails,
  falls back to a reverse lookup over all registered blocks, matching the
  explicit :registry-name field on the BlockSpec. This handles blocks whose
  DSL symbol name differs from their Minecraft registry path (e.g.
  wireless-node-advanced / \"node_advanced\").

  Args:
    registry-name: String - Minecraft registry name (e.g., \"node_advanced\")

  Returns:
    String - DSL block identifier (e.g., \"wireless-node-advanced\"), or nil if not found"
  [registry-name]
  (when registry-name
    (let [potential-id (clojure.string/replace registry-name #"_" "-")]
      (or (when (bdsl/get-block potential-id) potential-id)
          (some (fn [block-id]
                  (when-let [spec (bdsl/get-block block-id)]
                    (when (= registry-name (:registry-name spec))
                      block-id)))
                (bdsl/list-blocks))))))

(defn identify-block-from-full-name
  "Identify block ID from full Minecraft block name string.
  
  Handles formats like:
  - \"Block{my_mod:demo_block}\"
  - \"my_mod:demo_block\"
  - \"demo_block\"
  
  Args:
    block-name: String - Full block name from Minecraft
  
  Returns:
    String - DSL block identifier, or nil if not found"
  [^String block-name]
  (when block-name
    (let [;; Extract registry name from various formats
          registry-name (cond
                          ;; Format: "Block{my_mod:demo_block}"
                          (.contains block-name "{")
                          (-> block-name
                              (clojure.string/split #"[{}]")
                              second
                              (clojure.string/split #":")
                              last)
                          
                          ;; Format: "my_mod:demo_block"
                          (.contains block-name ":")
                          (last (clojure.string/split block-name #":"))
                          
                          ;; Format: "demo_block"
                          :else block-name)]
      (identify-block-from-registry-name registry-name))))

;; Initialization
;; ============================================================

(defn sync-handlers-from-dsl!
  "Synchronize event handlers from block DSL definitions.

  This function reads all block specifications from the DSL and registers
  their event handlers (if defined). Should be called after block definitions
  are loaded.

  This is called automatically during initialization, but can be called
  manually if needed (e.g., hot-reloading)."
  []
  (log/info "Synchronizing event handlers from block DSL...")
  (let [blocks (bdsl/list-blocks)]
    (log/info "  Found" (count blocks) "blocks to sync")
    (doseq [block-id blocks]
      (when-let [block-spec (bdsl/get-block block-id)]
        (let [events (:events block-spec)]
          (log/debug "  Syncing events for block:" block-id)
          ;; Register :on-right-click handler
          (when-let [on-right-click (:on-right-click events)]
            (log/debug "    Registering :on-right-click handler")
            (register-block-event-handler! block-id :on-right-click on-right-click))

          ;; Register :on-break handler
          (when-let [on-break (:on-break events)]
            (log/debug "    Registering :on-break handler")
            (register-block-event-handler! block-id :on-break on-break))

          ;; Register :on-place handler
          (when-let [on-place (:on-place events)]
            (log/debug "    Registering :on-place handler")
            (register-block-event-handler! block-id :on-place on-place)))))
    (log/info "Synchronized" (count @block-event-handlers) "block event handlers")))

(defn init-event-metadata!
  "Initialize event metadata system.
  
  Called during mod initialization to sync handlers from DSL."
  []
  (sync-handlers-from-dsl!))

;; ============================================================
;; Player Event Handlers (Ability System)
;; ============================================================

(defonce player-event-handlers
  ^{:doc "Registry of player lifecycle and ability event handlers.
  Structure: {event-type-keyword -> handler-fn}
  event-type-keyword examples:
    :player/logged-in  :player/logged-out  :player/respawn  :player/clone
    :player/tick       :player/death
    :ability/tick      (server tick for ability resource recovery)"}
  (atom {}))

(defn register-player-event-handler!
  "Register a handler for a player-level event type.
  Only one handler per event-type is supported (latest registration wins).

  Args:
    event-type: Keyword – e.g. :player/tick, :ability/tick
    handler-fn: (fn [ctx]) where ctx is a platform-neutral event map"
  [event-type handler-fn]
  (when handler-fn
    (swap! player-event-handlers assoc event-type handler-fn)
    (log/info "Registered player event handler for" event-type)))

(defn get-player-event-handler
  "Return the handler-fn for event-type, or nil."
  [event-type]
  (get @player-event-handlers event-type))

(defn has-player-event-handler?
  [event-type]
  (some? (get-player-event-handler event-type)))
