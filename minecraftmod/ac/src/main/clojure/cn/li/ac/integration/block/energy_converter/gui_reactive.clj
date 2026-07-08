(ns cn.li.ac.integration.block.energy-converter.gui-reactive
  "Complete reactive replacement for energy_converter/gui.clj screen rendering.
   Container/slot/sync logic delegates to the old gui.clj's public defns
   (create-container, get-slot-count, etc.) — those functions contain the
   generator/receiver derived-mode + schema-sync logic and are reused as-is
   rather than reimplemented, avoiding behavior drift.

   The old screen showed only the wireless panel (no inventory tab, no
   histogram — the block has zero I/O slots), so this does not go through
   cn.li.ac.gui.block-gui-reactive (which always builds an inv+wireless
   tab pair); it builds a bare page_wireless.xml runtime instead, matching
   the old single-page layout exactly."
  (:require [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.wireless.gui.tab-reactive :as wireless-tab]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.integration.block.energy-converter.gui :as old-gui]))

;; ============================================================================
;; Reactive screen — bare wireless panel, role derived from block-id
;; ============================================================================

(defn create-screen
  [container menu _player]
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/rework/new/page_wireless.xml"))
        _ (rt/build! r spec)
        role (if (= @(:wireless-mode container) :generator) :generator :receiver)]
    (wireless-tab/attach-panel! r {:role role :container container :menu menu})
    {:type :reactive-container-screen
     :runtime r
     :container container
     :menu menu
     :size-dx 0
     :size-dy 21}))

;; ============================================================================
;; Registration — delegates container/slot/sync logic to old-gui (as-is)
;; ============================================================================

(defonce-guard converter-gui-reactive-installed?)

(defn register-converter-guis-reactive!
  []
  (with-init-guard converter-gui-reactive-installed?
    (slot-schema/register-slot-schema!
      {:schema-id :energy-converter
       :slots []})
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :energy-converter)
      (merge (gui-manifest/gui-registration :energy-converter)
        {:container-predicate old-gui/converter-container?
         :container-fn old-gui/create-container
         :screen-fn create-screen
         :server-menu-sync-fn old-gui/server-menu-sync!
         :validate-fn old-gui/still-valid?
         :close-fn old-gui/on-close
         :button-click-fn old-gui/handle-button-click!
         :slot-count-fn old-gui/get-slot-count
         :slot-get-fn old-gui/get-slot-item
         :slot-set-fn old-gui/set-slot-item!
         :slot-can-place-fn old-gui/can-place-item?
         :slot-changed-fn old-gui/slot-changed!}))
    (log/info "Energy Converter GUI initialized (reactive: wireless panel, gui-id 14)")))
