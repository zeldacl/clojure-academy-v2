(ns cn.li.ac.gui.block-gui-reactive
  "Small compatibility helpers for migrated machine containers.
   Screen construction delegates to Presentation Container; no XML or legacy
   node runtime is used here."
  (:require [cn.li.ac.gui.presentation-container :as presentation-container]
            [cn.li.mcmod.client.platform-bridge :as bridge]))

(defn hist-buffer [value-fn max-fn]
  {:type :buffer :value-fn value-fn :max-fn max-fn :color 0xFFCC8844})
(defn hist-energy [color] {:type :energy :color (or color 0xFF4488CC)})
(defn hist-capacity [color] {:type :capacity :color (or color 0xFF44CC88)})

(defn update-signals! [_] nil)

(defn create-screen
  [{:keys [container menu player schema-id template-id]
    :or {schema-id :default template-id "academy:machine_container"}}]
  (presentation-container/presentation-screen-data
    container menu player schema-id template-id))

(defn open! [screen-data title]
  (when-let [mount-fn (:mount-fn screen-data)]
    (let [{:keys [mount on-close]} (mount-fn nil)]
      (bridge/call-adapter :presentation-open-screen! mount (or title "Machine") on-close)
      mount)))
