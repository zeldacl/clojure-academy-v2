# 宸ョ▼甯冨眬涓庡懡鍚嶇┖闂达紙浜虹被鍙锛?
涓?Cursor 瑙勫垯 [`.cursor/rules/project-structure.mdc`](../../.cursor/rules/project-structure.mdc) 鎻忚堪涓€鑷达紱**浠ユ湰鏂囦笌 `settings.gradle` 涓哄噯** 淇敼甯冨眬鏃讹紝璇峰悓姝ユ洿鏂拌瑙勫垯鏂囦欢锛岄伩鍏嶅垎鍙夈€?
## 椤跺眰 Gradle 妯″潡

| 妯″潡 | 鑱岃矗 |
|------|------|
| **`api`** | 瀵瑰 Java API锛堝浜掓搷浣滅敤鐨勬帴鍙ｅ寘锛夛紝鏃?Clojure 娓告垙閫昏緫 |
| **`mcmod`** | 骞冲彴鏃犲叧锛氬崗璁€丏SL銆乣protocol.metadata`銆佷簨浠?GUI/NBT 绛夊厓鏁版嵁锛?*绂佹** `net.minecraft.*` 涓?Loader API |
| **`ac`** | 娓告垙鍐呭涓庡煙閫昏緫锛?*绂佹**鐩存帴寮曠敤 Forge/Fabric/Minecraft API锛涢€氳繃 `mcmod` 涓庣害瀹氳竟鐣屼氦浜?|
| **`forge target`** | Forge 鍏ュ彛銆佹敞鍐屻€佹ˉ鎺?Java銆佸疄鐜?`mcmod` 鍗忚锛涘厑璁搁€氳繃鍙楁帶杩愯鏃舵ˉ鎺ヤ娇鐢?`ac` 鑳藉姏 |
| **`fabric target`** | 鍙€?Fabric 閫傞厤锛涢粯璁ゅ彲鑳芥湭鍦?`settings.gradle` 涓?`include` |

## 渚濊禆绾㈢嚎锛堜互鈥滈潤鎬佽€﹀悎鈥濈害鏉熶负涓伙級

- **绂佹** `ac` 瀵?`forge target` 寤虹珛闈欐€佷緷璧栵紙鍛藉悕绌洪棿/绫讳緷璧栵級銆?- **绂佹**鍦?`mcmod` 涓?`ac` 涓紩鍏ュ钩鍙?API锛坄net.minecraft.*` / Forge/Fabric锛夈€?- **鍏佽** `forge target` 瀵?`ac` 杩涜鍙楁帶杩愯鏃舵ˉ鎺ワ紙鍔ㄦ€佸叆鍙ｏ級锛岀敤浜庤閰嶄笌骞冲彴缁戝畾銆?- 杩愯鏃舵ˉ鎺ュ繀椤伙細
  1. 鏈夋槑纭叆鍙ｅ嚱鏁帮紱
  2. 鍦ㄦ枃妗ｄ腑鍙拷韪紱
  3. 涓嶆妸璺ㄥ眰瀹炵幇缁嗚妭鍥哄寲涓虹ǔ瀹?API銆?
## 婧愮爜璺緞涓?Clojure 鍛藉悕绌洪棿

- **`mcmod`**锛歚mcmod/src/main/clojure/cn/li/mcmod/...` 鈫?鍛藉悕绌洪棿鍓嶇紑 **`cn.li.mcmod.*`**
- **`ac`**锛歚ac/src/main/clojure/cn/li/ac/...` 鈫?**`cn.li.ac.*`**
- **`forge target`**锛歚platform-src/loader/forge/src/main/clojure/cn/li/forge1201/...` 鈫?**`cn.li.forge1201.*`**

### `mcmod` 鍏抽敭鍩虹鍛藉悕绌洪棿锛圓OT/杩愯鏃讹級

- `mcmod/src/main/clojure/cn/li/mcmod/aot.clj` 鈫?`cn.li.mcmod.aot`
  - 缂栬瘧鏈熸娴嬩笌杩愯鏈熸姢鏍忥細`compiling?` / `compile-context` / `ensure-runtime!`
- `mcmod/src/main/clojure/cn/li/mcmod/runtime/deferred.clj` 鈫?`cn.li.mcmod.runtime.deferred`
  - 缁熶竴鎯版€у垵濮嬪寲鎸佹湁鍣紙鏇夸唬骞冲彴鍚勮嚜瀹炵幇锛夛紝闃叉 AOT 鏈熼棿璇Е鍙?registry/bootstrap 璺緞

璧勬簮涓庢敞鍐岀敤 id 浠嶄互鏍圭洰褰?**`gradle.properties`** 鐨?`mod_id`锛堝 `my_mod`锛夈€?*`assets/my_mod/`**銆乣data/my_mod/` 涓哄噯銆?
## 鏁版嵁缁堢锛坄ac/terminal`锛?
鍑芥暟寮忔媶鍒嗭紝鏈嶅姟绔笌瀹㈡埛绔懡鍚嶇┖闂村垎绂伙細

| 璺緞 | 鍛藉悕绌洪棿鍓嶇紑 | 璇存槑 |
|------|----------------|------|
| `ac/.../terminal/model.clj` 绛?| `cn.li.ac.terminal.*` | 浼氳瘽鐘舵€併€乣catalog`銆佺綉缁溿€乣messages` |
| `ac/.../terminal/client/` | `cn.li.ac.terminal.client.*` | Shell銆乺untime銆乤pp launcher銆乣client.actions` 渚ц浇鍏ュ彛 |

缁存姢璇存槑瑙?[TERMINAL_SYSTEM_MAINTENANCE.md](../04-systems/TERMINAL_SYSTEM_MAINTENANCE.md)銆傚嬁鍐嶅紩鍏?`registry` 鍖呰灞傘€乣client/bridge` 鎴?manifest/SPI 寮忓簲鐢ㄦ敞鍐屻€?
## 鏃犵嚎鑳芥簮锛坄ac/wireless`锛?
鍗曚竴鍑芥暟寮忚繍琛屾椂锛屽澶栫粡 `cn.li.ac.wireless.api`锛?
| 璺緞 | 鍛藉悕绌洪棿 | 璇存槑 |
|------|----------|------|
| `wireless/api.clj` | `cn.li.ac.wireless.api` | 鏌ヨ涓庢嫇鎵戝懡浠ょ殑鍞竴瀵瑰鍏ュ彛 |
| `wireless/service/commands.clj`銆乣queries.clj` | `service.*` | 鍐?璇荤紪鎺掞紙妯″潡鍐咃級 |
| `wireless/domain/` | `domain.topology`銆乣domain.transfer` | 绾?world-state 涓庤兘閲忚鍒?|
| `wireless/data/world.clj` | `data.world` | **浠?*鐢熷懡鍛ㄦ湡涓?SavedData |
| `wireless/data/world_registry.clj` | `data.world-registry` | `transact!` 鍙彉鎻愪氦 |
| `wireless/runtime/effects.clj` | `runtime.effects` | capability 鑳介噺 IO |
| `ac/block/wireless_*` | `cn.li.ac.block.wireless-*` | 鏂瑰潡 tick/浜嬩欢锛涚粡 `wireless.api` 鏀规嫇鎵?|

濂戠害涓庢帓闅滐細[WIRELESS_REFACTOR_CONTRACTS.md](../05-wireless/WIRELESS_REFACTOR_CONTRACTS.md)銆乕WIRELESS_SYSTEM_MAINTENANCE.md](../04-systems/WIRELESS_SYSTEM_MAINTENANCE.md)銆傚凡鍒犻櫎 `topology-service`銆乣query-service`銆乣topology-index` 绛夊苟琛屽眰銆?
## 鑳藉姏绯荤粺锛坄ac/ability` + `ac/content/ability`锛?
**Reducer-only锛堝己鍒讹級**锛氱帺瀹剁姸鎬佸敮涓€鍐欒矾寰勪负 `command-runtime` 鈫?`reducer` 鈫?`runtime-store`锛涘壇浣滅敤璧?`effects.interpreter`銆傛棤 `context-registry` 闂ㄩ潰銆佹棤 `:sync-*-data` reducer 鍛戒护銆佹棤 `update-context!` 鏃佽矾銆?
| 璺緞 | 鍛藉悕绌洪棿 | 璇存槑 |
|------|----------|------|
| `ability/service/command_runtime.clj` | `service.command-runtime` | 鍛戒护鎵ц澹?|
| `ability/service/reducer.clj` | `service.reducer` | 鐜╁鐘舵€佸綊绾?|
| `ability/service/context_dispatcher.clj` | `service.context-dispatcher` | Context transport + lifecycle + 鍚堝苟璇伙紙`:as ctx`锛?|
| `ability/service/context_manager.clj` | `service.context-manager` | 鏈嶅姟绔?activate / keepalive / abort |
| `ability/service/context_skill_state.clj` | `service.context-skill-state` | 鎶€鑳戒晶璇诲啓鍏ュ彛锛坄:as ctx-skill`锛?|
| `ability/service/context_projection.clj` | `service.context-projection` | 浠?store 鎶曞奖璇?|
| `ability/effects/*` | `effects.*` | 鏈嶅姟绔晥鏋滐紙鍕挎仮澶?`ability/server/effect/*`锛?|
| `ability/adapters/runtime_bridge.clj` | `adapters.runtime-bridge` | 瀹夎 mcmod hooks |
| `content/ability/*` | `cn.li.ac.content.ability.*` | 鍚勬妧鑳?`defskill` 瀹炵幇 |

缁存姢璇存槑锛歔ABILITY_SYSTEM_MAINTENANCE.md](../04-systems/ABILITY_SYSTEM_MAINTENANCE.md)銆?
## CGUI MSDF 瀛椾綋锛坄platform-src/minecraft/version/mc-1201` + `ac` 娉ㄥ唽锛?
闆惰祫婧?MTSDF 闃村奖瀛椾綋锛屾浛浠ｅ凡鍒犻櫎鐨?TTF virtual-pack 鏍堛€侰GUI 浣滅敤鍩燂紙`:ac-normal` / `:ac-bold` / `:ac-italic`锛夛紱HUD 涓庡叾瀹?vanilla 鏂囨湰涓嶅湪姝よ矾寰勩€?
| 璺緞 | 鍛藉悕绌洪棿 / 绫?| 璇存槑 |
|------|----------------|------|
| `platform-src/minecraft/version/mc-1201/.../font/msdf/*.java` | `cn.li.mc1201.client.font.msdf.*` | STB 鍔犺浇銆乣MsdfEngine` MTSDF 鐢熸垚銆佸椤?atlas锛圠RU + 寮傛棰勭儤鐒欙級銆乣GlyphProvider` SPI銆乣MsdfRenderTypes`銆乻hader uniform |
| `platform-src/minecraft/version/mc-1201/.../font/msdf_setup.clj` | `cn.li.mc1201.client.font.msdf-setup` | 绯荤粺瀛椾綋鎺㈡祴 鈫?`MsdfFontManager` 鍒濆鍖?|
| `platform-src/minecraft/version/mc-1201/.../font/msdf_tick.clj` | `cn.li.mc1201.client.font.msdf-tick` | ClientTick 鍙戝厜鍛煎惛绛夊姩鐢?|
| `platform-src/minecraft/version/mc-1201/.../gui/cgui/font.clj` | `cn.li.mc1201.gui.cgui.font` | CGUI 妗ワ細`text-width` / `draw-text!`銆佸垎娈?MSDF/vanilla銆乸er-glyph 鏍囧織锛堥《鐐硅壊钃濋€氶亾浣?3 浣嶏級銆乣with-text-fx` |
| `platform-src/minecraft/version/mc-1201/.../gui/reactive/render.clj` | `cn.li.mc1201.gui.reactive.render` | `:text` 缁勪欢鎺ョ嚎锛坄render-text!`/`bake-text!`锛屾浛浠ｅ凡鍒犻櫎鐨勬棫 `gui/cgui/renderer.clj`锛?|
| `ac/.../client/font_init.clj` | `cn.li.ac.client.font-init` | 娉ㄥ唽 `:ac-*` 瀛椾綋鍏抽敭瀛楋紙flag-only锛?|
| `ac/.../assets/my_mod/shaders/core/msdf_text.*` | 鈥?| MSDF 鏂囨湰 shader锛坢edian + fwidth AA + 鏁堟灉灞傦級 |
| `platform-src/loader/forge/.../ForgeClientRenderRegistry` | 鈥?| `RegisterShadersEvent` 娉ㄥ唽 `my_mod:msdf_text` |
| `platform-src/loader/fabric/.../FabricClientRenderSetup` | 鈥?| `CoreShaderRegistrationCallback` 鍚岀瓑娉ㄥ唽 |

**Follow-up 鑳藉姏锛堝凡瀹炵幇锛?*锛氬崟瀛楃涓插唴 per-glyph bold/outline/glow锛坰hader 瑙ｇ爜椤剁偣鑹叉爣蹇楋級锛沗getGlyph` 瑙﹀彂鐨勫紓姝?MTSDF 棰勭儤鐒欙紱atlas LRU锛堥粯璁?4096 glyph锛夛紱`start-glow-breath!` ClientTick 鍛煎惛鍙戝厜銆?
**瀛楀彿濂戠害**锛歚:font-size N` = 灞忓箷涓?**N 鍍忕礌楂?*锛堝悓 LambdaLib2 `FontOption.fontSize`锛夈€係TB 鍦?8px em 涓嬬儤鐒欙紱`scale = N / 8`銆傚竷灞€ quad 鐢?typographic bounds锛屼笉鍚?MSDF bake padding锛圓C 鍘熺増鏍呮牸 cell 24脳1.4 浠呬綔鍙傝€冨父閲?`AC_CHAR_SIZE`锛夈€?
## 鍙嶅簲寮?UI 妗嗘灦杩佺Щ锛堝凡瀹屾垚锛?
鏃?CGUI 妗嗘灦锛坄mcmod/gui/cgui_core.clj` 绛?8 鏂囦欢銆乣platform-src/minecraft/version/mc-1201/gui/cgui/{renderer,traversal,input,runtime,assets}.clj`锛変笌鍏舵秷璐硅€呭凡鍏ㄩ儴鍒犻櫎锛屾浛鎹负 `platform-src/minecraft/version/mc-1201/gui/reactive/*`锛坰ignal 椹卞姩銆乨irty-gated锛? `mcmod` signal core銆備繚鐣欙細`platform-src/minecraft/version/mc-1201/gui/cgui/font.clj`锛圡SDF 妗ワ紝浠嶅湪鐢級銆乣mcmod/gui/tabbed_gui.clj` + `spec.clj`锛堝钩鍙版棤鍏崇殑 tab 鍚屾/GUI spec 鏍￠獙锛岃 `gui/menu/proxy.clj`銆乣gui/slots/sync.clj`銆乣gui/reactive/host_container.clj`銆佸涓?`*_reactive.clj` 娑堣垂锛岄潪鏃ф鏋舵畫鐣欙級銆?
**骞冲彴鍒濆鍖?*锛欶orge / Fabric `client/init` 璋冪敤 `msdf-setup/init!`锛涘瓧浣?tick 鐢卞悇 loader 鐨?client lifecycle 鎺ュ叆銆?
## Scripted 閫昏緫鍒嗗彂锛坄platform-src/minecraft/version/mc-1201` + 骞冲彴娉ㄥ唽锛?
BlockEntity 涓?Mob 鐑矾寰勭粡 Java 鎺ュ彛 + reify bundle锛屾棤杩愯鏈?`^:dynamic` 鏌ヨ〃銆傝瑙?[SCRIPTED_LOGIC_DISPATCH.md](../04-systems/SCRIPTED_LOGIC_DISPATCH.md)銆?
| 璺緞 | 璇存槑 |
|------|------|
| `platform-src/minecraft/version/mc-1201/.../block/logic/*.java` | `ITile*Logic`銆乣TileLogicBundle`銆乣IScriptedBlock` |
| `platform-src/minecraft/version/mc-1201/.../block/logic_compile.clj`銆乣logic_pipeline.clj` | tile bundle 缂栬瘧涓庡畨瑁?|
| `platform-src/minecraft/version/mc-1201/.../entity/ScriptedMobEntity.java`銆乣entity/logic/*` | Mob bundle 涓?`ScriptedEntityLogicRegistry` |
| `platform-src/minecraft/version/mc-1201/.../entity/mob_logic_compile.clj`銆乣mob_logic_pipeline.clj` | mob bundle 缂栬瘧涓庡畨瑁?|
| `mcmod/.../block/tile_kind.clj` | 澹版槑鏈?tile-kind 榛樿锛堝悎骞朵簬 compile锛?|
| `platform-src/loader/forge/.../registry/content_registration.clj` | 娉ㄥ唽鏈熻皟鐢?pipeline |

## 鏂板鍐呭搴旇惤鍦ㄤ綍澶?
1. 鍦?**`mcmod`** 鎵╁睍 DSL / 鍏冩暟鎹?/ 鍗忚锛堣嫢娑夊強鏂版娊璞★級銆?2. 鍦?**`ac`** 瀹炵幇鏂瑰潡銆佺墿鍝併€佷笟鍔￠€昏緫锛屼娇鐢?`defblock` / `defitem` 绛夊啓鍏?`mcmod` registry銆?3. 浠呭湪闇€瑕?Loader 涓撶敤鑳舵按鏃舵敼 **`forge target`**锛堟垨鍚敤鍚庣殑 Fabric 妯″潡锛夛紝骞朵繚鎸侀€傞厤灞傝杽銆?4. 鏂扮粓绔簲鐢細鏀?`terminal/catalog.clj` + `terminal/client/apps/*` + `client/apps.clj` 鐨?`launchers`銆?
