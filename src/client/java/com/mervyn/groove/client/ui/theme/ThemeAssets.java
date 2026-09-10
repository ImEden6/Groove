package com.mervyn.groove.client.ui.theme;

import com.mervyn.groove.GrooveMod;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the fixed texture-id manifest (docs/SEQUENCER-UI-ARCHITECTURE.md section 4)
 * for one theme namespace. Only "vanilla" ships inside the mod jar; clockwork/crt/tactical
 * are distributed as optional resourcepacks (see scratch/tools/package_resourcepacks.py),
 * so a sprite that isn't backed by any currently-loaded pack falls back to a single
 * bundled hazard texture instead of Minecraft's checkerboard missing-texture placeholder.
 */
public final class ThemeAssets {
    private static final ResourceLocation HAZARD = spriteId("sequencer/hazard_missing_asset");
    private static final Map<ResourceLocation, Boolean> PRESENT = new ConcurrentHashMap<>();

    static {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    private static final ResourceLocation ID = GrooveMod.id("theme_assets_reload");

                    @Override
                    public ResourceLocation getFabricId() { return ID; }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        PRESENT.clear();
                    }
                });
    }

    private ThemeAssets() {}

    /** The GUI sprite id to blit for {@code name} under {@code theme}, or the shared
     *  hazard sprite if no loaded pack (mod jar included) actually backs it. */
    public static ResourceLocation sprite(String theme, String name) {
        ResourceLocation id = spriteId("sequencer/" + theme + "/" + name);
        boolean present = PRESENT.computeIfAbsent(id, ThemeAssets::exists);
        return present ? id : HAZARD;
    }

    private static ResourceLocation spriteId(String path) {
        return GrooveMod.id(path);
    }

    public static String panelFile(PanelKind kind) {
        return switch (kind) {
            case MAIN -> "panel_main";
            case DRAWER -> "panel_drawer";
            case TRANSPORT -> "panel_transport";
        };
    }

    public static String portFile(PortState state) {
        return switch (state) {
            case FREE -> "port_free";
            case COMPATIBLE -> "port_compatible";
            case INCOMPATIBLE -> "port_incompatible";
            case MAGNET -> "port_magnet";
        };
    }

    private static boolean exists(ResourceLocation spriteId) {
        ResourceLocation pngPath = spriteId.withPath(p -> "textures/gui/sprites/" + p + ".png");
        return Minecraft.getInstance().getResourceManager().getResource(pngPath).isPresent();
    }
}
