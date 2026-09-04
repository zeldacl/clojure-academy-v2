(ns cn.li.ac.terminal.client.presentation-terminal
  "Terminal surface controller backed by Presentation Runtime."
  (:require [cn.li.ac.terminal.catalog :as catalog]
            [cn.li.ac.terminal.client.runtime :as terminal]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private start-x 65.0)
(def ^:private start-y 155.0)
(def ^:private step-x 180.0)
(def ^:private step-y 180.0)
(def ^:private app-w 151.0)
(def ^:private app-h 151.0)
(def ^:private apps-per-row 3)

(defn- app-tiles
  "Flatten installed apps into composite IR for the terminal grid (main shell parity).

   Each layer carries absolute :layout-x/:layout-y for hit/arrange, and local
   :x/:y (0-based) for CompositeSpec paint offsets inside that arranged rect."
  [installed-ids page]
  (let [apps (catalog/installed-apps-in-display-order installed-ids)
        page (int (or page 0))
        page-size 9
        slice (->> apps (drop (* page page-size)) (take page-size) vec)]
    (vec
     (mapcat
      (fn [idx app]
        (let [col (mod idx apps-per-row)
              row (quot idx apps-per-row)
              lx (+ start-x (* col step-x))
              ly (+ start-y (* row step-y))
              icon (or (:icon app) "academy:textures/guis/apps/about/icon.png")
              label (or (:name app) (name (:id app)))]
          [{:kind :image :src "academy:textures/guis/data_terminal/app_back.png"
            :layout-x lx :layout-y ly :x 0.0 :y 0.0 :w app-w :h app-h
            :rgba (unchecked-int 0xFFFFFFFF)
            :app-id (:id app)}
           {:kind :image :src icon
            :layout-x (+ lx 9.0) :layout-y (+ ly 32.0) :x 0.0 :y 0.0 :w 110.0 :h 110.0
            :rgba (unchecked-int 0xA0FFFFFF)
            :app-id (:id app)}
           {:kind :text :text (str label)
            :layout-x lx :layout-y (+ ly 148.0) :x 0.0 :y 0.0 :w app-w :h 21.0
            :font-size 14.0 :rgba (unchecked-int 0xFFFFFFFF)
            :app-id (:id app)}]))
      (range (count slice))
      slice))))

(defn- view-state [owner]
  (let [snap (terminal/state-snapshot owner)
        installed (:installed-apps snap)
        page (int (or (:page snap) 0))
        n (count (catalog/installed-apps-in-display-order installed))]
    (merge snap
           {:username (str "[" (or (:player-name snap) "player") "]")
            :appcount (str n " Applications")
            :apps-composite (app-tiles installed page)
            :loading-label (if (:loading? snap) "Loading" "")
            :page (str page)
            :query (str (:query snap ""))
            :modal (:modal snap)
            :loading? (boolean (:loading? snap))})))

(defn terminal-view-model [owner dispatch-action!]
  (let [state (view-state owner)
        vm (presentation/mount-view!
             {:view-id :academy.app/terminal
              :host-kind :screen
              :state state
              :dispatch-action!
              (fn [action payload _current]
                (dispatch-action! action payload)
                (view-state owner))})]
    (assoc vm
           :refresh! (fn []
                       (presentation/present! vm (view-state owner)))
           :state (:state vm))))

(defn mount-terminal! [owner dispatch-action!]
  (terminal/ensure-owner! owner)
  (terminal-view-model owner dispatch-action!))

(defn open-screen! [owner dispatch-action! on-close]
  (let [vm (mount-terminal! owner dispatch-action!)]
    (client-bridge/call-adapter :presentation-open-screen!
                                 (:mount vm) "Terminal" on-close)
    vm))
