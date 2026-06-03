(ns cn.li.ac.block.gui.sync
  "Shared GUI container sync helpers for AC block GUIs.

  This namespace keeps schema-runtime wiring out of individual block GUI files so
  simple machines can declare their schema once and reuse the same sync/get/apply
  and close behavior."
  (:require [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]))

(defn create-schema-container
  "Create the common container map for schema-backed block GUIs.

  `base` is merged before schema atoms, allowing callers to provide
  `:tile-entity`, `:player`, `:container-type`, and any GUI-local values. When
  `state` is omitted, the current tile state is read with `common/get-tile-state`."
  [schema tile player container-type & [{:keys [state base]}]]
  (let [state (or state (common/get-tile-state tile) {})
        sync-get (schema-runtime/build-get-sync-data-fn schema)]
    (merge {:tile-entity tile
            :player player
            :container-type container-type
            :sync-get sync-get
            :sync-last-sent (atom nil)
            :sync-has-sent? (atom false)}
           base
           (schema-runtime/build-gui-atoms schema state))))

(defn schema-sync-fns
  "Build reusable sync lifecycle functions for a GUI schema.

  Optional callbacks:
  - `:after-sync!` receives the container after server->client atom sync.
  - `:after-apply!` receives container and payload after payload apply.
  - `:after-close!` receives the container after schema close resets."
  [schema & [{:keys [after-sync! after-apply! after-close!]}]]
  (let [sync-to-client* (schema-runtime/build-sync-to-client-fn schema)
        get-sync-data* (schema-runtime/build-get-sync-data-fn schema)
        apply-sync-data* (schema-runtime/build-apply-sync-data-fn schema)
        on-close* (schema-runtime/build-on-close-fn schema)]
    {:sync-to-client! (fn [container]
                        (sync-to-client* container)
                        (when after-sync!
                          (after-sync! container)))
     :get-sync-data (fn [container]
                      (get-sync-data* container))
     :apply-sync-data! (fn [container data]
                         (apply-sync-data* container data)
                         (when after-apply!
                           (after-apply! container data)))
     :on-close (fn [container]
                 (on-close* container)
                 (when after-close!
                   (after-close! container)))}))

(defn sync-tick!
  "Increment an optional ticker atom and run a sync function."
  [container sync-to-client! & [{:keys [ticker-key derived-sync!]}]]
  (when-let [ticker (and ticker-key (get container ticker-key))]
    (swap! ticker inc))
  (sync-to-client! container)
  (when derived-sync!
    (derived-sync! container)))
