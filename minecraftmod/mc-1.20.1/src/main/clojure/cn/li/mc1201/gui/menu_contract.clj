(ns cn.li.mc1201.gui.menu-contract
  "Shared GUI menu contract for platform adapters.

  Platform modules should implement this protocol for concrete menu/container
  objects, while shared/business code can operate on abstract slots/state." )

(defprotocol IMenuContract
  (menu-id [this] "Unique menu identifier")
  (menu-title [this] "Localized or raw menu title")
  (menu-slot-count [this] "Number of slots")
  (menu-player-inventory-start [this] "Player inventory start slot index")
  (menu-player-inventory-end [this] "Player inventory end slot index"))

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
