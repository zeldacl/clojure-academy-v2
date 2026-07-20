(ns cn.li.mc1201.gui.menu.contract
  "Shared GUI menu contract for platform adapters.

  Expected map keys:
  - :menu-id - Unique menu identifier
  - :menu-title - Localized or raw menu title
  - :menu-slot-count - Number of slots
  - :menu-player-inventory-start - Player inventory start slot index
  - :menu-player-inventory-end - Player inventory end slot index")

;; Wrapper functions for map-based menu contracts

(defn menu-id [menu]
  (:menu-id menu))

(defn menu-title [menu]
  (:menu-title menu))

(defn menu-slot-count [menu]
  (:menu-slot-count menu))

(defn menu-player-inventory-start [menu]
  (:menu-player-inventory-start menu))

(defn menu-player-inventory-end [menu]
  (:menu-player-inventory-end menu))

(defn menu-layout
  "Return normalized layout map for a menu contract implementation."
  [menu]
  {:id (menu-id menu)
   :title (menu-title menu)
   :slot-count (int (menu-slot-count menu))
   :player-inventory-start (int (menu-player-inventory-start menu))
   :player-inventory-end (int (menu-player-inventory-end menu))})

(defn valid-menu-layout?
  [layout]
  (let [{:keys [slot-count player-inventory-start player-inventory-end]} layout]
    (and (integer? slot-count)
         (integer? player-inventory-start)
         (integer? player-inventory-end)
         (<= 0 player-inventory-start)
         (<= player-inventory-start player-inventory-end)
         (< player-inventory-end (max 1 slot-count)))))
