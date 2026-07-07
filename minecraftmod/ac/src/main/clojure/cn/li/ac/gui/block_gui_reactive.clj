(ns cn.li.ac.gui.block-gui-reactive
  "Shared reactive block GUI helper — single migration path for all ~10 block GUIs.
   Replaces the tech-ui-common + assemble-tech-ui-root + per-GUI boilerplate pattern.

   Usage per block GUI (e.g. solar_gen):
     (require '[cn.li.ac.gui.block-gui-reactive :as bgui])
     (defn create-screen [container menu player]
       (bgui/create-screen
         {:page-xml \"guis/rework/page_solar.xml\"
          :texture-name \"solar\"
          :container container
          :menu menu
          :histograms [(bgui/hist-buffer energy-fn max-energy-fn)]
          :properties {:gen_speed speed-fn :status status-fn}
          :wireless? true
          :wireless-role :generator}))"
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
            [cn.li.mcmod.ui.dsl :as dsl]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Config API (replaces tech-ui-common constructors)
;; ============================================================================

(defn hist-buffer [value-fn max-fn]
  {:type :buffer :value-fn value-fn :max-fn max-fn :color 0xFFCC8844})

(defn hist-energy [color]
  {:type :energy :color (or color 0xFF4488CC)})

(defn hist-capacity [color]
  {:type :capacity :color (or color 0xFF44CC88)})

;; ============================================================================
;; Screen creation (replaces tech-ui/assemble-tech-ui-root)
;; ============================================================================

(defn create-screen
  "Create reactive tech-ui screen config.
   Options:
   :page-xml      - resource path to XML layout
   :texture-name  - ui_block texture suffix
   :container     - container atom map
   :menu          - Minecraft container menu
   :histograms    - [(hist-buffer ...) ...]
   :properties    - {:key value-fn ...}
   :wireless?     - include wireless tab
   :wireless-role - :generator or :machine"
  [{:keys [page-xml texture-name container menu histograms properties wireless? wireless-role]}]
  (let [r (rt/create-runtime)
        ;; Load inventory page from XML
        spec (ui-xml/load-spec (modid/namespaced-path (or page-xml "guis/rework/page_inv.xml")))
        root-idx (rt/build! r spec)
        ;; Set ui_block texture
        tex-path (sig/signal-o
                   (modid/asset-path "textures"
                     (str "guis/ui/ui_" (or texture-name "inv") ".png")))
        _ (ui/bind! r :ui_block :src tex-path)
        ;; Create dynamic signals
        signals {:energy (sig/signal-d 0.0)
                 :max-energy (sig/signal-d 1.0)
                 :status (sig/signal-o "IDLE")
                 :gen-speed (sig/signal-o "0IF/T")
                 :progress (sig/signal-d 0.0)}
        _ (doseq [[k s] signals] (rt/put-user-signal! r k s))]
    ;; Return config map (used by update-fn + open-fn)
    {:runtime r
     :signals signals
     :container container
     :menu menu
     :properties properties
     :histograms histograms}))

;; ============================================================================
;; Per-frame update (replaces on-frame polling)
;; ============================================================================

(defn update-signals!
  "Read container atoms, sset signals. Called each frame by screen host."
  [{:keys [runtime signals container properties]}]
  (let [safe-val #(some-> % deref)]
    ;; Core signals
    (sig/sset-d! (:energy signals) (double (or (safe-val (:energy container)) 0.0)))
    (sig/sset-d! (:max-energy signals) (max 1.0 (double (or (safe-val (:max-energy container)) 1.0))))
    (sig/sset-o! (:status signals) (or (safe-val (:status container)) "IDLE"))
    (sig/sset-o! (:gen-speed signals)
                 (format "%.2fIF/T" (double (or (safe-val (:gen-speed container)) 0.0))))
    (sig/sset-d! (:progress signals) (double (or (safe-val (:progress container)) 0.0)))))

;; ============================================================================
;; Open screen (replaces create-screen-fn + registration)
;; ============================================================================

(defn open!
  "Open reactive block GUI screen via bridge."
  [{:keys [runtime]} title]
  (bridge/open-reactive-screen! runtime (or title "Machine")))
