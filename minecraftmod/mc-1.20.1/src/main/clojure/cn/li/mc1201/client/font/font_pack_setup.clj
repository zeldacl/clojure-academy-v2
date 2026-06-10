(ns cn.li.mc1201.client.font.font-pack-setup
  "Runtime system font detection and virtual resource-pack injection.

  Orchestrates the pipeline:
    1. Detect a suitable system TrueType font (system-font-detector).
    2. Look up calibrated TTF provider parameters (font-profiles).
    3. Build the virtual PackResources (SystemFontVirtualPack).
    4. Register it via Forge's AddPackFindersEvent.
    5. Set the CGUI fallback-scale-factor when no system font is available.

  Called from Java (@Mod.EventBusSubscriber → AddPackFindersEvent handler)
  via ClojureInterop/invoke."
  (:require [cn.li.mc1201.client.font.system-font-detector :as detector]
            [cn.li.mc1201.client.font.font-profiles :as profiles]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.packs PackType]
           [net.minecraft.server.packs.repository Pack Pack$ResourcesSupplier Pack$Info Pack$Position PackSource RepositorySource]
           [net.minecraft.network.chat Component]
           [net.minecraft.world.flag FeatureFlagSet]
           [cn.li.mc1201.font SystemFontVirtualPack]
           [net.minecraftforge.event AddPackFindersEvent]))

(defonce ^:private setup-called? (atom false))

(defn- setup-fallback!
  "Configure the CGUI fallback scale factor for the minecraft:default bitmap font.
  The factor compensates for the thicker pixel strokes so the visual size matches
  the TrueType reference."
  []
  (let [scale (double (:fallback-scale profiles/fallback-profile))]
    (log/info "No system font detected; CGUI fallback-scale: %.2f" (float scale))
    (cgui-font/set-fallback-scale-factor! scale)
    nil))

(defn- setup-ttf-pack!
  "Detect system font, build calibrated JSON, create virtual pack, register it
  via AddPackFindersEvent. Returns true if a pack was registered."
  [^AddPackFindersEvent event]
  (when-let [{:keys [path profile ext]} (detector/detect-system-font)]
    (if-let [{:keys [size shift] :as prof} (profiles/profile-for profile)]
      (let [pack-resources (SystemFontVirtualPack. path ext (profiles/build-font-json prof ext))
            resources-supplier
            (reify Pack$ResourcesSupplier
              (open [_ _id]
                pack-resources))
            pack-info
            (Pack$Info. (Component/literal "Injected system TrueType font for smooth text rendering.")
                        15 15    ;; resource-format data-format (MC 1.20.1 = 15)
                        (FeatureFlagSet/of)
                        true)    ;; hidden — don't show in resource-pack UI
            pack
            (Pack/create
              "my_mod/system_font"
              (Component/literal "System Font Pack")
              true                                   ;; required — always enabled
              resources-supplier
              pack-info
              PackType/CLIENT_RESOURCES
              Pack$Position/TOP                      ;; top priority
              true                                   ;; hidden
              PackSource/DEFAULT)
            repo-source
            (reify RepositorySource
              (loadPacks [_ consumer]
                (.accept consumer pack)))]
        (log/info "System font detected: %s (profile :%s, size %.1f)"
                  (str path) (name profile) (double size))
        (cgui-font/set-cgui-font-base-height! size)
        (cgui-font/set-fallback-scale-factor! 1.0)
        (.addRepositorySource event repo-source)
        true)
      (do
        (log/warn "System font found but no calibration profile for :%s; falling back"
                  (name profile))
        false))))

(defn on-add-pack-finders!
  "Handler for Forge AddPackFindersEvent (called from Java @Mod.EventBusSubscriber).

  Only acts on CLIENT_RESOURCES — server-side data packs are ignored.
  Idempotent: only runs detection + registration once."
  [^AddPackFindersEvent event]
  (when (= PackType/CLIENT_RESOURCES (.getPackType event))
    (when (compare-and-set! setup-called? false true)
      (try
        (when-not (setup-ttf-pack! event)
          (setup-fallback!))
        (catch Exception e
          (log/error "System font pack setup failed: %s" (ex-message e))
          (setup-fallback!))))))
