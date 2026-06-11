(ns cn.li.mc1201.client.font.font-pack-setup
  "Runtime system font detection and virtual resource-pack injection.

  Orchestrates the pipeline:
    1. Detect a suitable system TrueType font (system-font-detector).
    2. Look up calibrated TTF provider parameters (font-profiles).
    3. Build three font JSON variants (normal index 0, bold index 1, italic index 0).
    4. Build the virtual PackResources (SystemFontVirtualPack).
    5. Register it through a platform-provided repository-source consumer.
    6. Set the CGUI fallback-scale-factor and base-height."
  (:require [cn.li.mc1201.client.font.system-font-detector :as detector]
            [cn.li.mc1201.client.font.font-profiles :as profiles]
            [cn.li.mc1201.gui.cgui.font :as cgui-font]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.packs PackType]
           [net.minecraft.server.packs.repository Pack Pack$ResourcesSupplier Pack$Info Pack$Position PackSource RepositorySource]
           [net.minecraft.network.chat Component]
           [net.minecraft.world.flag FeatureFlagSet]
           [cn.li.mc1201.font SystemFontVirtualPack]
           [java.util.function Consumer]))

(defonce ^:private setup-called? (atom false))

(defn- setup-fallback!
  []
  (let [scale (double (:fallback-scale profiles/fallback-profile))]
    (log/info "No system font detected; CGUI fallback-scale: %.2f" (float scale))
    (cgui-font/set-fallback-scale-factor! scale)
    nil))

(defn- setup-ttf-pack!
  [^Consumer add-repository-source!]
  (when-let [{:keys [path profile ext]} (detector/detect-system-font)]
    (if-let [{:keys [size shift] :as prof} (profiles/profile-for profile)]
      (let [jsons (profiles/build-font-jsons prof ext)
            pack-resources (SystemFontVirtualPack. path ext
                              (:normal jsons) (:bold jsons) (:italic jsons))
            resources-supplier
            (reify Pack$ResourcesSupplier
              (open [_ _id] pack-resources))
            pack-info
            (Pack$Info. (Component/literal "Injected system TrueType font for smooth text rendering.")
                        15 15 (FeatureFlagSet/of) true)
            pack
            (Pack/create "my_mod/system_font"
              (Component/literal "System Font Pack")
              true resources-supplier pack-info
              PackType/CLIENT_RESOURCES Pack$Position/TOP
              true PackSource/DEFAULT)
            repo-source
            (reify RepositorySource
              (loadPacks [_ consumer] (.accept consumer pack)))]
        (log/info "System font detected: %s (profile :%s, size %.1f)"
                  (str path) (name profile) (double size))
        (cgui-font/set-cgui-font-base-height! size)
        (cgui-font/set-fallback-scale-factor! 1.0)
        (when-let [cf (:color-factor prof)]
          (cgui-font/set-ttf-color-factor! cf))
        (.accept add-repository-source! repo-source)
        true)
      (do
        (log/warn "System font found but no calibration profile for :%s; falling back"
                  (name profile))
        false))))

(defn on-add-pack-finders!
  [^Consumer add-repository-source!]
  (when (compare-and-set! setup-called? false true)
    (try
      (when-not (setup-ttf-pack! add-repository-source!)
        (setup-fallback!))
      (catch Exception e
        (log/error "System font pack setup failed: %s" (ex-message e))
        (setup-fallback!)))))
