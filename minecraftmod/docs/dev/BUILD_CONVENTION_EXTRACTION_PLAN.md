# Build Convention Extraction Plan

## Purpose

瑙勫垝濡備綍鎶婂綋鍓嶆暎钀藉湪骞冲彴妯″潡锛堝挨鍏?`forge target`锛変腑鐨勬瀯寤洪瓟娉曟彁鍙栦负缁熶竴 convention锛屼粠鑰岄檷浣庢柊澧?Loader / 鏂扮増鏈ā鍧楃殑澶嶅埗鎴愭湰銆?
## 褰撳墠鐥涚偣

- `platform-src/loader/forge/build.gradle` 鎵挎媴浜嗗ぇ閲?AOT銆乺emap銆乻ourceSets 娉ㄥ叆銆佸瓧鑺傜爜淇銆佽繍琛屼换鍔″墠缃緷璧栭€昏緫銆?- `platform-src/loader/fabric/build.gradle` 涔熷瓨鍦ㄤ笌鍏剁浉浼肩殑鍏变韩婧愮爜娉ㄥ叆妯″紡銆?- 鏂板骞冲彴鎴栫増鏈椂锛屽鏄撳鍒?build 鏂囦欢骞舵墜宸ヤ慨鏀癸紝瀵艰嚧閫昏緫婕傜Щ銆?
## 褰撳墠浠撳簱閲嶅椤规槧灏勶紙Forge/Fabric锛?
| 閲嶅涓婚 | Forge (`platform-src/loader/forge/build.gradle`) | Fabric (`platform-src/loader/fabric/build.gradle`) | 澶勭悊鍐崇瓥 |
|---|---|---|---|
| 娉ㄥ叆鍏变韩婧愮爜 sourceSets | `sourceSets.main.{clojure,java,resources}` 娉ㄥ叆 `ac/mcmod/api` | 鍚岀粨鏋勬敞鍏?`ac/mcmod/api` | **宸叉娊鍙?*鍒?`gradle/platform_build_helpers.gradle` 鐨?`configureInjectedPlatformSources` |
| Clojure 鏍稿績渚濊禆锛坕mplementation锛?| `org.clojure:*` 涓変欢濂?| `org.clojure:*` 涓変欢濂?| **宸叉娊鍙?*鍒?`addSharedClojureRuntimeDeps` |
| Shadow 鎵撳寘瑙勫垯 | `shadowJar` 鍖呭惈 Clojure 杩愯鏃?| 鐩稿悓瑙勫垯 | **宸叉娊鍙?*鍒?`configureSharedClojureShadowJar` |
| remap 杈撳叆绛栫暐 | `remapJar.inputFile = jar.archiveFile` | `remapJar.inputFile = shadowJar.archiveFile` | **淇濈暀宸紓**锛坙oader 琛屼负涓嶅悓锛?|
| 杩愯鏃堕澶栧簱閰嶇疆 | `forgeRuntimeLibrary` 闇€鏄惧紡澹版槑 | Fabric 鏃犺閰嶇疆 | **淇濈暀宸紓**锛堜粎 Forge锛?|
| AOT 杈撳嚭鍚堝苟/LVT 娓呯悊 | `stripClojureLVT` + `copyClojureClassesToJavaOutput` + run/check 渚濊禆 | 褰撳墠鏈噰鐢ㄥ悓濂楅摼璺?| **鏆備笉鎶藉彇**锛堝厛楠岃瘉 Fabric 鏄惁闇€瑕侊級 |

> 娉細鏈疆鍙仛鈥滀綆椋庨櫓鍏辨€ф彁鍙栤€濓紝涓嶆敼鍙?loader-specific 琛屼负銆?
## 寤鸿鍒嗛樁娈靛疄鏂?
### Phase 1 鈥?鏂囨。鍖栫幇鏈夎鍒?
鍏堢‘璁ゅ苟璁板綍浠ヤ笅瑙勫垯锛?
- 鍝簺 sourceSets 娉ㄥ叆鏄繀闇€鐨勩€?- AOT 杈撳嚭鐩綍涓?Loom / Loader 杩愯鏃剁殑鍏崇郴銆?- `stripClojureLVT` 鐨勮Е鍙戞潯浠朵笌蹇呰鎬с€?- `copyClojureClassesToJavaOutput` 鐨勭洰鐨勩€?- 杩愯浠诲姟涓轰綍渚濊禆杩欎簺鍓嶇疆浠诲姟銆?
### Phase 2 鈥?鎶藉彇 shared Gradle helpers

浼樺厛鎶藉彇锛?
- 鍏变韩 sourceSets 娉ㄥ叆閰嶇疆
- 鍏变韩 Clojure AOT 閰嶇疆
- 鍏变韩 class copy / bytecode strip 浠诲姟
- 鍏变韩 compile/check 璇婃柇浠诲姟娉ㄥ唽鍑芥暟

### Phase 3 鈥?鎻愮偧 platform convention plugin

鐩爣锛?
- 閫氳繃 `buildSrc` 鎴栨湰鍦?convention plugin 涓?`forge-*` / `future-loader target` / `fabric-*` 鎻愪緵鍏叡鏋勫缓楠ㄦ灦銆?- 骞冲彴妯″潡鍙０鏄庡樊寮傞」锛氫緷璧栥€佸叆鍙ｃ€佹槧灏勩€丩oader API 涓庡皯閲忎换鍔″樊寮傘€?
## 棰勬湡鏀剁泭

- 鏂板骞冲彴妯″潡鏇存帴杩戔€滃～妯℃澘鈥濊€屼笉鏄€滃鍒跺嚑鐧捐 Gradle 骞剁绁封€濄€?- 鏇村鏄撶粺涓€淇 Clojure AOT / remap 闂銆?- 鍑忓皯澶氬钩鍙?build 鑴氭湰鐨勮涓烘紓绉汇€?
## 椋庨櫓鎺у埗

- 涓嶅缓璁竴寮€濮嬪氨褰诲簳鏀归€犱负鍏ㄦ柊鏋勫缓浣撶郴銆?- 鍏堣鐜版湁涓荤嚎 Forge 淇濇寔鍙繍琛岋紝鍐嶉€愭鎻愬彇鍏辨€с€?- 姣忔彁鍙栦竴姝ワ紝閮介渶瑕佷繚鐣?compile/run 绾у洖褰掗獙璇併€?
## 瀹屾垚鏍囧噯

鑻ュ悗缁弧瓒充互涓嬫潯浠讹紝鍙涓?convention 鎶藉彇鍩烘湰鎴愬姛锛?
1. 鏂板缓涓€涓?`loader-version` 妯″潡鏃讹紝涓嶅啀闇€瑕佸鍒跺ぇ娈电幇鏈?build 鑴氭湰銆?2. AOT / remap / class copy 鐨勫叧閿€昏緫鍙淮鎶や竴浠戒富瀹炵幇銆?3. `Forge`銆乣new loader component`銆乣Fabric` 骞冲彴妯″潡鐨勫樊寮傚ぇ閮ㄥ垎鏀舵暃涓轰緷璧栦笌鍏ュ彛宸紓銆?
