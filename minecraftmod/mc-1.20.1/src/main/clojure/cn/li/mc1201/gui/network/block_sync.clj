(ns cn.li.mc1201.gui.network.block-sync
  "Shared message id for server→client block GUI state pushes.

  Must stay in sync with `cn.li.mcmod.gui.sync-api/BLOCK-GUI-STATE-MSG-ID`.
  Loaders serialize {:msg-id BLOCK-GUI-STATE-MSG-ID :payload sync-data} and send
  via the platform GUI RPC channel (Forge SimpleChannel / Fabric s2c).")

(def BLOCK-GUI-STATE-MSG-ID
  "ac/gui-block-state-sync")
