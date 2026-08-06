#!/usr/bin/env python3
"""Promote MSDF shared pieces (1.20.1 + 1.21.1) into mcbase; 26.2 has no MSDF pipeline."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MC1201 = ("mc-1.20.1", "mc1201")
MC1211 = ("mc-1.21.1", "mc1211")
# Only 1201/1211 participate; 26.2 keeps vanilla-advance font.clj


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("write", path.relative_to(ROOT))


def rewrite_exact(replacements: list[tuple[str, str]], roots: list[Path]) -> None:
    """Replace exact tokens; prefer longer tokens first to avoid prefix collisions."""
    reps = sorted(replacements, key=lambda x: -len(x[0]))
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.suffix not in {".java", ".clj"}:
                continue
            text = path.read_text(encoding="utf-8", errors="surrogateescape")
            orig = text
            for a, b in reps:
                text = text.replace(a, b)
            if text != orig:
                path.write_text(text, encoding="utf-8", errors="surrogateescape", newline="\n")
                print("rewrite", path.relative_to(ROOT))


def delete_versioned(rel: str, versions: list[tuple[str, str]]) -> None:
    for folder, ns in versions:
        for kind in ("java", "clojure"):
            p = ROOT / f"platform-src/minecraft/{folder}/src/main/{kind}/cn/li/{ns}/{rel}"
            if p.exists():
                p.unlink()
                print("delete", p.relative_to(ROOT))


def promote_java_from_1201(rel: str) -> None:
    src = ROOT / f"platform-src/minecraft/mc-1.20.1/src/main/java/cn/li/mc1201/{rel}"
    text = src.read_text(encoding="utf-8").replace("cn.li.mc1201", "cn.li.mcbase")
    write(ROOT / f"platform-src/minecraft/base/src/main/java/cn/li/mcbase/{rel}", text)
    delete_versioned(rel, [MC1201, MC1211])


def promote_clj_from_1201(rel: str) -> None:
    src = ROOT / f"platform-src/minecraft/mc-1.20.1/src/main/clojure/cn/li/mc1201/{rel}"
    text = src.read_text(encoding="utf-8").replace("cn.li.mc1201", "cn.li.mcbase")
    write(ROOT / f"platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/{rel}", text)
    delete_versioned(rel, [MC1201, MC1211])


def write_msdf_font_manager() -> None:
    write(
        ROOT
        / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/client/font/msdf/MsdfFontManager.java",
        """package cn.li.mcbase.client.font.msdf;

import cn.li.mcver.ResourceLocations;
import cn.li.mcmod.ModId;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * Shadow font facade shared by 1.20.1 / 1.21.1 CGUI.
 * Version loaders install {@link Backend} (FontSet / TrueType provider differs).
 */
public final class MsdfFontManager {

    private static final Logger LOGGER = LogManager.getLogger();

    public static final ResourceLocation SHADOW_FONT_ID =
            ResourceLocations.of(ModId.ID, "msdf_shadow");

    public static final float DESIGN_PIXEL_HEIGHT = 32.0f;
    public static final float CGUI_BASE_HEIGHT = 32.0f;

    public interface Backend {
        boolean init(Path fontPath);

        void shutdown();

        boolean hasGlyph(int codePoint);
    }

    private static final Backend DISABLED = new Backend() {
        @Override
        public boolean init(Path fontPath) {
            LOGGER.warn("MSDF backend not installed; path={}", fontPath);
            return false;
        }

        @Override
        public void shutdown() {}

        @Override
        public boolean hasGlyph(int codePoint) {
            return false;
        }
    };

    private static volatile Backend backend = DISABLED;

    private static Font shadowFont;
    private static FontSet shadowFontSet;
    private static volatile boolean bakeProbed;
    private static volatile boolean monospace;
    private static volatile float monospaceAdvance;

    private MsdfFontManager() {}

    public static void installBackend(final Backend b) {
        backend = b != null ? b : DISABLED;
    }

    /** Package-private for version backends. */
    public static void bindShadowFont(final Font font, final FontSet fontSet) {
        shadowFont = font;
        shadowFontSet = fontSet;
        bakeProbed = false;
    }

    public static void clearShadowFont() {
        shadowFont = null;
        shadowFontSet = null;
        bakeProbed = false;
    }

    public static boolean init(final Path fontPath) {
        synchronized (MsdfFontManager.class) {
            if (shadowFont != null) {
                return true;
            }
            return backend.init(fontPath);
        }
    }

    public static void shutdown() {
        backend.shutdown();
        clearShadowFont();
    }

    public static void setMonospace(final boolean v) {
        monospace = v;
    }

    public static boolean isMonospace() {
        return monospace;
    }

    public static float monospaceAdvance() {
        return monospaceAdvance;
    }

    public static boolean isAvailable() {
        return hasFontFace();
    }

    public static boolean hasFontFace() {
        return shadowFont != null;
    }

    public static boolean hasGlyph(final int codePoint) {
        return backend.hasGlyph(codePoint);
    }

    public static Font shadowFont() {
        return shadowFont;
    }

    public static float cguiBaseHeight() {
        return CGUI_BASE_HEIGHT;
    }

    public static void clientTick() {
        if (!bakeProbed && shadowFont != null) {
            bakeProbed = true;
            try {
                monospaceAdvance = shadowFont.width(Component.literal("0"));
                final int wi = shadowFont.width(Component.literal("i"));
                final int wW = shadowFont.width(Component.literal("W"));
                final int wCjk = shadowFont.width(Component.literal("中"));
                LOGGER.info("MSDF probe: i={} W={} U+4E2D={} monoAdv={}",
                        wi, wW, wCjk, monospaceAdvance);
            } catch (Exception e) {
                LOGGER.warn("MSDF probe failed", e);
            }
        }
    }
}
""",
    )


def write_1201_backend() -> None:
    write(
        ROOT
        / "platform-src/minecraft/mc-1.20.1/src/main/java/cn/li/mc1201/client/font/msdf/MsdfFontBackend.java",
        """package cn.li.mc1201.client.font.msdf;

import cn.li.mcbase.client.font.msdf.MsdfFontManager;
import cn.li.mcbase.client.font.msdf.MonospaceAwareGlyphProvider;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.FontSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;

/** 1.20.1 TrueTypeGlyphProvider + FontSet.reload backend. */
public final class MsdfFontBackend implements MsdfFontManager.Backend {

    private static final Logger LOGGER = LogManager.getLogger();
    private static MsdfFontFace face;

    public static void install() {
        MsdfFontManager.installBackend(new MsdfFontBackend());
    }

    @Override
    public boolean init(final Path fontPath) {
        try {
            final Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                LOGGER.debug("MSDF init deferred: Minecraft not ready");
                return false;
            }

            face = new MsdfFontFace(fontPath, MsdfFontManager.DESIGN_PIXEL_HEIGHT);
            final GlyphProvider vanilla = face.glyphProvider();
            final GlyphProvider provider = new MonospaceAwareGlyphProvider(vanilla);

            final FontSet fontSet =
                    new FontSet(mc.getTextureManager(), MsdfFontManager.SHADOW_FONT_ID);
            fontSet.reload(List.of(provider));
            final Font shadowFont = new Font(rl -> fontSet, false);
            MsdfFontManager.bindShadowFont(shadowFont, fontSet);

            if (RenderSystem.isOnRenderThread()) {
                fontSet.getGlyph('A');
                fontSet.getGlyph(0x4E2D);
            }

            LOGGER.info(
                    "MSDF shadow font loaded from {} (A={}, U+4E2D={})",
                    fontPath,
                    face.hasGlyph('A'),
                    face.hasGlyph(0x4E2D));
            return true;
        } catch (Exception e) {
            LOGGER.error("MSDF font init failed for {}", fontPath, e);
            shutdown();
            return false;
        }
    }

    @Override
    public void shutdown() {
        if (face != null) {
            face.close();
            face = null;
        }
        MsdfFontManager.clearShadowFont();
    }

    @Override
    public boolean hasGlyph(final int codePoint) {
        return face != null && face.hasGlyph(codePoint);
    }
}
""",
    )


def write_1211_backend() -> None:
    write(
        ROOT
        / "platform-src/minecraft/mc-1.21.1/src/main/java/cn/li/mc1211/client/font/msdf/MsdfFontBackend.java",
        """package cn.li.mc1211.client.font.msdf;

import cn.li.mcbase.client.font.msdf.MsdfFontManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

/**
 * 1.21.1 FreeType TrueTypeGlyphProvider / FontSet.reload port pending.
 * Installs a no-op backend so shared CGUI/MSDF Clojure keeps compiling.
 */
public final class MsdfFontBackend implements MsdfFontManager.Backend {

    private static final Logger LOGGER = LogManager.getLogger();

    public static void install() {
        MsdfFontManager.installBackend(new MsdfFontBackend());
    }

    @Override
    public boolean init(final Path fontPath) {
        LOGGER.warn("MSDF shadow font disabled on 1.21.1 (FreeType glyph provider pending); path={}", fontPath);
        return false;
    }

    @Override
    public void shutdown() {}

    @Override
    public boolean hasGlyph(final int codePoint) {
        return false;
    }
}
""",
    )


def write_monospace_provider() -> None:
    write(
        ROOT
        / "platform-src/minecraft/base/src/main/java/cn/li/mcbase/client/font/msdf/MonospaceAwareGlyphProvider.java",
        """package cn.li.mcbase.client.font.msdf;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import it.unimi.dsi.fastutil.ints.IntSet;

import javax.annotation.Nullable;

/**
 * Wraps a vanilla {@link GlyphProvider} so monospace mode fixes advances via {@link MSDFAwareGlyph}.
 */
public final class MonospaceAwareGlyphProvider implements GlyphProvider {

    private final GlyphProvider delegate;

    public MonospaceAwareGlyphProvider(final GlyphProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    @Nullable
    public GlyphInfo getGlyph(final int codePoint) {
        final GlyphInfo original = delegate.getGlyph(codePoint);
        if (original == null) {
            return null;
        }
        return new MSDFAwareGlyph(original,
                MsdfFontManager.isMonospace(),
                MsdfFontManager.monospaceAdvance());
    }

    @Override
    public IntSet getSupportedGlyphs() {
        return delegate.getSupportedGlyphs();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
""",
    )


def wire_backend_install() -> None:
    """Call MsdfFontBackend.install() early in client init Java/Clojure."""
    # Java render registries already touch MSDF — add install at class load via static in backend
    # Prefer explicit call from forge/fabric client init clojure and Java shader setup.
    for path, class_name in (
        (
            ROOT
            / "platform-src/loader/forge-1.20.1/src/main/java/cn/li/forge1201/client/render/ForgeClientRenderRegistry.java",
            "cn.li.mc1201.client.font.msdf.MsdfFontBackend",
        ),
        (
            ROOT
            / "platform-src/loader/fabric-1.20.1/src/main/java/cn/li/fabric1201/client/FabricClientRenderSetup.java",
            "cn.li.mc1201.client.font.msdf.MsdfFontBackend",
        ),
        (
            ROOT
            / "platform-src/loader/neoforge-1.21.1/src/main/java/cn/li/neoforge1211/client/render/ForgeClientRenderRegistry.java",
            "cn.li.mc1211.client.font.msdf.MsdfFontBackend",
        ),
    ):
        text = path.read_text(encoding="utf-8")
        if "MsdfFontBackend.install" in text:
            continue
        # insert install before first MsdfRenderTypes use or at start of register method
        if "MsdfFontBackend" not in text:
            # add import after package imports block — after first import line of msdf if present
            text = text.replace(
                "import cn.li.mcbase.client.font.msdf.MsdfRenderTypes;",
                "import cn.li.mcbase.client.font.msdf.MsdfRenderTypes;\n"
                f"import {class_name};",
            )
            if f"import {class_name};" not in text:
                # still versioned import path before rewrite
                text = text.replace(
                    "import cn.li.mc1201.client.font.msdf.MsdfRenderTypes;",
                    "import cn.li.mc1201.client.font.msdf.MsdfRenderTypes;\n"
                    "import cn.li.mc1201.client.font.msdf.MsdfFontBackend;",
                )
                text = text.replace(
                    "import cn.li.mc1211.client.font.msdf.MsdfRenderTypes;",
                    "import cn.li.mc1211.client.font.msdf.MsdfRenderTypes;\n"
                    "import cn.li.mc1211.client.font.msdf.MsdfFontBackend;",
                )
        # call install in registerShaders / setup method
        if "MsdfFontBackend.install();" not in text:
            # forge style
            text = text.replace(
                "public static void registerShaders(",
                "public static void registerShaders(",
            )
            # insert at beginning of onInitializeClient / register method bodies is fragile;
            # use static initializer on the registry class
            if "static {" not in text:
                text = text.replace(
                    "public final class ForgeClientRenderRegistry {",
                    "public final class ForgeClientRenderRegistry {\n"
                    "    static { MsdfFontBackend.install(); }",
                )
                text = text.replace(
                    "public final class FabricClientRenderSetup {",
                    "public final class FabricClientRenderSetup {\n"
                    "    static { MsdfFontBackend.install(); }",
                )
        path.write_text(text, encoding="utf-8", newline="\n")
        print("wire", path.relative_to(ROOT))


def main() -> None:
    # 1) Promote identical closed Java
    promote_java_from_1201("client/font/msdf/MsdfRenderTypes.java")
    promote_java_from_1201("client/font/msdf/MSDFAwareGlyph.java")
    write_monospace_provider()
    delete_versioned("client/font/msdf/MonospaceAwareGlyphProvider.java", [MC1201, MC1211])

    # 2) Shared manager + version backends; remove old managers
    write_msdf_font_manager()
    delete_versioned("client/font/msdf/MsdfFontManager.java", [MC1201, MC1211])
    write_1201_backend()
    write_1211_backend()
    # 1211 Face unused after stub — delete
    delete_versioned("client/font/msdf/MsdfFontFace.java", [MC1211])

    # 3) Clojure setup/tick + cgui font (1201/1211 identical)
    promote_clj_from_1201("client/font/msdf_setup.clj")
    promote_clj_from_1201("client/font/msdf_tick.clj")
    promote_clj_from_1201("gui/cgui/font.clj")

    # 4) Retarget references (1201/1211 loaders + mc trees only; leave mc-26.2 alone)
    roots = [
        ROOT / "platform-src/minecraft/mc-1.20.1",
        ROOT / "platform-src/minecraft/mc-1.21.1",
        ROOT / "platform-src/minecraft/base",
        ROOT / "platform-src/loader/forge-1.20.1",
        ROOT / "platform-src/loader/fabric-1.20.1",
        ROOT / "platform-src/loader/neoforge-1.21.1",
    ]
    rewrite_exact(
        [
            ("cn.li.mc1201.client.font.msdf-setup", "cn.li.mcbase.client.font.msdf-setup"),
            ("cn.li.mc1211.client.font.msdf-setup", "cn.li.mcbase.client.font.msdf-setup"),
            ("cn.li.mc1201.client.font.msdf-tick", "cn.li.mcbase.client.font.msdf-tick"),
            ("cn.li.mc1211.client.font.msdf-tick", "cn.li.mcbase.client.font.msdf-tick"),
            ("cn.li.mc1201.gui.cgui.font", "cn.li.mcbase.gui.cgui.font"),
            ("cn.li.mc1211.gui.cgui.font", "cn.li.mcbase.gui.cgui.font"),
            ("cn.li.mc1201.client.font.msdf.MsdfRenderTypes", "cn.li.mcbase.client.font.msdf.MsdfRenderTypes"),
            ("cn.li.mc1211.client.font.msdf.MsdfRenderTypes", "cn.li.mcbase.client.font.msdf.MsdfRenderTypes"),
            ("cn.li.mc1201.client.font.msdf.MsdfFontManager", "cn.li.mcbase.client.font.msdf.MsdfFontManager"),
            ("cn.li.mc1211.client.font.msdf.MsdfFontManager", "cn.li.mcbase.client.font.msdf.MsdfFontManager"),
            ("cn.li.mc1201.client.font.msdf.MSDFAwareGlyph", "cn.li.mcbase.client.font.msdf.MSDFAwareGlyph"),
            ("cn.li.mc1211.client.font.msdf.MSDFAwareGlyph", "cn.li.mcbase.client.font.msdf.MSDFAwareGlyph"),
            ("cn.li.mc1201.client.font.msdf.MonospaceAwareGlyphProvider", "cn.li.mcbase.client.font.msdf.MonospaceAwareGlyphProvider"),
            ("cn.li.mc1211.client.font.msdf.MonospaceAwareGlyphProvider", "cn.li.mcbase.client.font.msdf.MonospaceAwareGlyphProvider"),
            # Clojure import vectors
            ("[cn.li.mc1201.client.font.msdf MsdfFontManager]", "[cn.li.mcbase.client.font.msdf MsdfFontManager]"),
            ("[cn.li.mc1211.client.font.msdf MsdfFontManager]", "[cn.li.mcbase.client.font.msdf MsdfFontManager]"),
        ],
        roots,
    )

    wire_backend_install()
    # Fix imports after rewrite may have changed MsdfRenderTypes import path
    wire_backend_install()

    print("MSDF PROMOTE DONE")


if __name__ == "__main__":
    main()
