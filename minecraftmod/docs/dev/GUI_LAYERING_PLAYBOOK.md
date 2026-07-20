# GUI Layering Playbook (Forge/Fabric + mc1201 shared)

鏈枃妗ｆ弿杩版湰浠撳簱 GUI 閲嶆瀯鍚庣殑鏈€缁堝垎灞備笌鍏ュ彛绾﹀畾銆?
## 鐩爣

- 璁?`platform-src/minecraft/version/mc-1201` 鎵挎媴 **Minecraft API 鐩稿叧涓?loader-agnostic** 鐨?GUI 鏍稿績銆?- 璁?`forge target` / `fabric target` 鍙繚鐣?loader 鐢熷懡鍛ㄦ湡銆佹敞鍐屻€佺綉缁滀笌骞冲彴妗ユ帴澹炽€?- 骞冲彴鑿滃崟妗ユ帴鐩存帴 require `cn.li.mcmod.gui.adapter.platform-registry`锛汚C 閫氳繃 `cn.li.ac.gui.platform-adapter/install-into-mcmod!` 娉ㄥ叆鍥炶皟銆?
## 鍒嗗眰鑱岃矗

### 1) Shared (`platform-src/minecraft/version/mc-1201/src/main/{clojure,java}/cn/li/mc1201/gui`)

涓昏鍖呭惈锛?
- `cgui/runtime.clj`銆乣cgui/renderer.clj`銆乣cgui/input.clj`銆乣cgui/assets.clj`銆乣cgui/traversal.clj`锛氬叡浜?CGUI 杩愯鏃躲€佹覆鏌撱€佽緭鍏ャ€佽祫婧愪笌閬嶅巻瀹炵幇
- `menu/container.clj`銆乣menu/proxy.clj`锛氬叡浜彍鍗曞鍣ㄩ€傞厤涓庤彍鍗?proxy 琛屼负
- `provider/common.clj`銆乣provider/dispatcher.clj`锛氬叡浜?provider helper 涓庡洖璋冨垎鍙?- `registry/common.clj`銆乣registry/open.clj`锛氬叡浜敞鍐屽寘瑁呬笌鎵撳紑閫昏緫
- `screen/registry.clj`銆乣screen/impl.clj`锛氬叡浜?screen 娉ㄥ唽寰幆涓?screen 瀹炵幇
- `slots/common.clj`銆乣slots/sync.clj`銆乣slots/tabbed.clj`锛氬叡浜Ы浣嶅竷灞€銆佸悓姝ヤ笌 tabbed 绾︽潫
- `network/packet.clj`锛氬叡浜?GUI packet/envelope 缂栬В鐮?SSOT
- `init/orchestrator.clj`銆乣init/checks.clj`锛氬叡浜?GUI phase manifest 缂栨帓涓庡垵濮嬪寲鑷
- `CMenuBridge.java`锛氬叡浜彍鍗曟ˉ Java 鍩虹被
- `CGuiContainerScreen.java`锛氬叡浜?container screen Java 鍩虹被

鏃х殑椤跺眰 `*_core` / `*_common` / `*_bridge` / `*_adapter` GUI Clojure 鏂囦欢宸插垹闄わ紝鍚庣画涓嶈鍐嶄綔涓哄吋瀹瑰３鎭㈠銆?
绾︽潫锛?
- 涓嶅緱寮曞叆 `net.minecraftforge.*` / `net.fabricmc.*`
- client-only 浠ｇ爜閫氳繃璋冪敤璺緞鎺у埗锛堜粎鍦?client 鍒濆鍖?灞忓箷鎵撳紑璺緞瑙﹀彂锛?
### 2) Forge (`platform-src/loader/forge/src/main/clojure/cn/li/forge1201/gui`)

淇濈暀鑱岃矗锛?
- `init.clj`锛欶orge 鐢熷懡鍛ㄦ湡闃舵鍏ュ彛
- `registry_impl.clj`锛歚DeferredRegister` / `IForgeMenuType` / `NetworkHooks`
- `network.clj`锛欶orge 缃戠粶閫氶亾娉ㄥ唽涓庢ˉ鎺?- `menu_bridge.clj` / `provider_bridge.clj` / `screen_impl.clj`锛氬钩鍙板３瑁呴厤鍏变韩鏍稿績锛堣皟鐢?`mc1201.gui.*` domain namespace锛?- `bridge.clj`锛欶acade锛堝澶栨ˉ鎺ュ叆鍙ｏ級

### 3) Fabric (`platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/gui`)

淇濈暀鑱岃矗锛?
- `init.clj`锛欶abric 鐢熷懡鍛ㄦ湡闃舵鍏ュ彛
- `registry_impl.clj`锛歚ScreenHandlerRegistry` + opening data + 瀹㈡埛绔噸寤哄鍣?- `network.clj`锛欶abric networking receiver
- `menu_bridge.clj` / `provider_bridge.clj` / `screen_impl.clj`锛氬钩鍙板３瑁呴厤鍏变韩鏍稿績锛堣皟鐢?`mc1201.gui.*` domain namespace锛?- `bridge.clj`锛欶acade锛堝澶栨ˉ鎺ュ叆鍙ｏ級

澶囨敞锛?
- Fabric 宸插榻愬叡浜?CGUI 瀹夸富璺緞锛坄CGuiContainerScreen + mc1201 cgui runtime`锛?- Fabric `menu_bridge` 宸茶ˉ榻?tabbed GUI 鐨勫悓姝ヤ笌妲戒綅浜や簰绾︽潫

## 缁熶竴鍏ュ彛

- 骞冲彴 GUI 浠ｇ爜缁熶竴渚濊禆锛歚cn.li.mcmod.gui.adapter.platform-registry` 涓?`cn.li.mcmod.gui.registry`
- 閬垮厤鐩存帴鍦ㄥ钩鍙板眰娣风敤鏃?`ac.*` GUI facade 鍛藉悕绌洪棿

## 鍒濆鍖栭『搴忓師鍒欙紙涓嶈寮鸿缁熶竴锛?
- Forge 涓?Fabric 鐨勫垵濮嬪寲椤哄簭鍏佽涓嶅悓锛?  - Forge锛歚DeferredRegister` / Common Setup / Client Setup
  - Fabric锛歚registry_impl/init!` + client/server network receiver 鍒嗙
- 鍏变韩鍖栫洰鏍囨槸鍏变韩鏍稿績閫昏緫锛屼笉鏄姽骞?loader 鐢熷懡鍛ㄦ湡宸紓

## 楠岃瘉寤鸿

鑷冲皯鎵ц锛?
- `cmd /c .\gradlew.bat :mcmod:compileJava :mcmod:compileClojure`
- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure`
- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure`
- `cmd /c .\gradlew.bat verifyArchitectureBoundaries verifyCurrentPlatforms`

骞跺仛浠ｈ〃鎬?GUI 鐑熸祴锛?
- 鏅€氬鍣?GUI锛團orge/Fabric锛?- tabbed CGUI 瀹瑰櫒锛團orge/Fabric锛?- 鏍囩鍒囨崲鍚庣殑妲戒綅浜や簰琛屼负涓€鑷存€?
