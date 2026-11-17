package com.amaguri.aibuddy.client;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;

import com.amaguri.aibuddy.entity.FakeMember;

/**
 * スキン名 -> ResolvableProfile、スキンのテクスチャID -> 平均色 のキャッシュ。
 * 平均色はテクスチャIDごとに1回だけ計算する（デフォルトスキン→本物のスキンに変わるとIDが変わるので再計算される）。
 */
public final class SkinColors {
	/** 読めなかったときの色（くすんだ水色）。 */
	private static final int FALLBACK = 0x507080;
	private static final Map<Identifier, Integer> AVERAGES = new HashMap<>();

	private SkinColors() {
	}

	/** サーバーが解決して同期したプロフィールで引く（毎フレーム新規作成しない）。未解決/未設定なら既定スキン。 */
	public static PlayerSkin skinOf(FakeMember member) {
		ResolvableProfile profile = member.getProfile();
		if (profile == null || ResolvableProfile.Static.EMPTY.equals(profile)) {
			return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin();
		}
		PlayerSkinRenderCache cache = Minecraft.getInstance().playerSkinRenderCache();
		return cache.getOrDefault(profile).playerSkin();
	}

	/** 水たまり・滴の色。胴70% + 顔30% の平均を 0.8 倍に暗くした 0xRRGGBB。 */
	public static int puddleRgb(PlayerSkin skin) {
		Identifier id = skin.body().texturePath();
		Integer c = AVERAGES.get(id);
		if (c == null) {
			c = compute(id);
			AVERAGES.put(id, c);
		}
		return c;
	}

	private static int compute(Identifier id) {
		NativeImage img = null;
		TextureContents owned = null;
		try {
			AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(id);
			// UNVERIFIED: ダウンロードしたスキンが DynamicTexture でピクセルを保持しているか
			if (tex instanceof DynamicTexture dyn && dyn.getPixels() != null && !dyn.getPixels().isClosed()) {
				img = dyn.getPixels();
			}
			if (img == null) {
				// デフォルトスキンなどリソースパック上の PNG
				owned = TextureContents.load(Minecraft.getInstance().getResourceManager(), id);
				img = owned.image();
			}
			if (img.getWidth() < 64 || img.getHeight() < 32) {
				return FALLBACK;
			}
			long[] torso = sum(img, 20, 20, 8, 12);
			long[] face = sum(img, 8, 8, 8, 8);
			double r = 0, g = 0, b = 0, w = 0;
			if (torso[3] > 0) {
				r += 0.7 * torso[0] / torso[3];
				g += 0.7 * torso[1] / torso[3];
				b += 0.7 * torso[2] / torso[3];
				w += 0.7;
			}
			if (face[3] > 0) {
				r += 0.3 * face[0] / face[3];
				g += 0.3 * face[1] / face[3];
				b += 0.3 * face[2] / face[3];
				w += 0.3;
			}
			if (w <= 0) {
				return FALLBACK;
			}
			int ri = (int) (r / w * 0.8);
			int gi = (int) (g / w * 0.8);
			int bi = (int) (b / w * 0.8);
			return (ri << 16) | (gi << 8) | bi;
		} catch (Exception | LinkageError e) {
			return FALLBACK;
		} finally {
			if (owned != null) {
				owned.close();
			}
		}
	}

	/** 矩形内の不透明ピクセルの R,G,B 合計と個数。 */
	private static long[] sum(NativeImage img, int x0, int y0, int w, int h) {
		long r = 0, g = 0, b = 0, n = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				int argb = img.getPixel(x, y); // UNVERIFIED: ARGB 並びであること
				if ((argb >>> 24) < 128) {
					continue;
				}
				r += (argb >> 16) & 0xFF;
				g += (argb >> 8) & 0xFF;
				b += argb & 0xFF;
				n++;
			}
		}
		return new long[] {r, g, b, n};
	}
}
