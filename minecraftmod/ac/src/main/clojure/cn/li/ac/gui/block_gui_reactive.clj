(ns cn.li.ac.gui.block-gui-reactive
  "Shared reactive block GUI helper — single migration path for all ~10 block GUIs.
   Replaces the tech-ui-common + assemble-tech-ui-root + per-GUI boilerplate pattern.

   Usage per block GUI (e.g. solar_gen):
     (require '[cn.li.ac.gui.block-gui-reactive :as bgui])
     (defn create-screen [container menu player]
       (bgui/create-screen
         {:page-xml \"guis/rework/new/page_solar.xml\"
          :texture-name \"solar\"
          :container container
          :menu menu
          :histograms [(bgui/hist-buffer energy-fn max-energy-fn)]
          :properties {:gen_speed speed-fn :status status-fn}
          :wireless? true
          :wireless-role :generator}))"
  (:require [cn.li.ac.gui.tech-ui-tabs-reactive :as tech-tabs]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.core :as ui]
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

(defn- inv-page-xml [page-xml]
  (or page-xml "guis/rework/new/page_inv.xml"))

(defn- wireless-pages [page-xml]
  [{:id tech-tabs/inv-tab-id :xml (inv-page-xml page-xml)}
   {:id "wireless" :xml "guis/rework/new/page_wireless.xml"}])

(defn update-signals!
  "Read container atoms, sset signals. Called each frame by screen host."
  [{:keys [signals container properties]}]
  (let [safe-val #(some-> % deref)]
    (sig/sset-d! (:energy signals) (double (or (safe-val (:energy container)) 0.0)))
    (sig/sset-d! (:max-energy signals) (max 1.0 (double (or (safe-val (:max-energy container)) 1.0))))
    (sig/sset-o! (:status signals) (or (safe-val (:status container)) "IDLE"))
    (sig/sset-o! (:gen-speed signals)
                 (format "%.2fIF/T" (double (or (safe-val (:gen-speed container)) 0.0))))
    (sig/sset-d! (:progress signals) (double (or (safe-val (:progress container)) 0.0)))
    (when-let [props properties]
      (doseq [[_k f] props]
        (when fn? (f))))))

(defn- screen-config [r signals container menu properties histograms
                      current-tab-atom tech-ui custom-bind!]
  {:type :reactive-container-screen
   :runtime r
   :signals signals
   :container container
   :menu menu
   :properties properties
   :histograms histograms
   :size-dx 31
   :size-dy 20
   :update-fn update-signals!
   :current-tab-atom current-tab-atom
   :tech-ui tech-ui
   :custom-bind! custom-bind!})

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
  [{:keys [page-xml texture-name container menu histograms properties wireless? wireless-role
            custom-bind!]}]
  (let [r (rt/create-runtime)
        pages (when wireless? (wireless-pages page-xml))
        spec (if wireless?
               (tech-tabs/build-tabbed-root-spec pages)
               (ui-xml/load-spec (modid/namespaced-path (inv-page-xml page-xml))))
        _ (rt/build! r spec)
        tex-path (sig/signal-o
                   (modid/asset-path "textures"
                     (str "guis/ui/ui_" (or texture-name "inv") ".png")))
        _ (ui/bind! r :ui_block :src tex-path)
        signals {:energy (sig/signal-d 0.0)
                 :max-energy (sig/signal-d 1.0)
                 :status (sig/signal-o "IDLE")
                 :gen-speed (sig/signal-o "0IF/T")
                 :progress (sig/signal-d 0.0)}
        _ (doseq [[k s] signals] (rt/put-user-signal! r k s))
        current-tab-atom (when wireless? (atom tech-tabs/inv-tab-id))
        tech-ui (when wireless?
                  (tech-tabs/attach-tab-ui! r pages current-tab-atom container menu))
        _ (when custom-bind! (custom-bind! r container signals))
        _ (when wireless? (rt/put-user-signal! r :wireless-role (sig/signal-o (name wireless-role))))]
    (screen-config r signals container menu properties histograms
                   current-tab-atom tech-ui custom-bind!)))

;; ============================================================================
;; Open screen (replaces create-screen-fn + registration)
;; ============================================================================

(defn open!
  "Open reactive block GUI screen via bridge."
  [{:keys [runtime]} title]
  (bridge/open-reactive-screen! runtime (or title "Machine")))
