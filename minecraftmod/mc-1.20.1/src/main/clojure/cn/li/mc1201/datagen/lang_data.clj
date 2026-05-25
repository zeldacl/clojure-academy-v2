(ns cn.li.mc1201.datagen.lang-data
  "Shared language file data for datagen.
  
  Provides base translations and merging logic for multi-language support
  (English and Simplified Chinese). Can be consumed by both Forge and Fabric
  lang providers."
  )

(defn base-lang-data
  "Get base translations for creative tab and Minecraft-owned generic commands.
  
  Returns: map with keys 'en_us.json' and 'zh_cn.json', each containing
  translation key→value pairs for commands and UI elements"
  []
  {"en_us.json"
   {"itemGroup.my_mod.items" "My Mod Items"
    "command.mcmod.grant_advancement.success" "Granted advancement %s to %s"
    "command.mcmod.grant_advancement.not_found" "Advancement not found: %s"}

   "zh_cn.json"
   {"itemGroup.my_mod.items" "My Mod Items"
    "command.mcmod.grant_advancement.success" "已授予进度 %s 给 %s"
    "command.mcmod.grant_advancement.not_found" "未找到进度: %s"}})

(defn merged-lang-data
  "Merge base translations with metadata-provided translations.
  
  Args:
    translation-maps-fn: function returning {:en_us {...} :zh_cn {...}}
                        from datagen metadata source
  
  Returns: map with keys 'en_us.json' and 'zh_cn.json', each containing
  merged base + metadata translations (metadata takes precedence on conflicts)"
  [translation-maps-fn]
  (let [{:keys [en_us zh_cn]} (translation-maps-fn)
        base (base-lang-data)]
    {"en_us.json" (merge (get base "en_us.json") en_us)
     "zh_cn.json" (merge (get base "zh_cn.json") zh_cn)}))
