package com.amaguri.aibuddy.gametest;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Blocks;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;

/**
 * 実機クライアントで FakeMember を空中の草の台に出し、/aibuddy melt 0, 0.3, 0.6, 0.9, 1.0 を順にかけて
 * 正面からスクリーンショットを撮る（run/screenshots/melt_*.png）。溶けが画面に出ているかを目で確かめる用。
 */
public class MeltShotsClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("aibuddy-meltshots");
	private static final float[] STEPS = {0f, 0.3f, 0.6f, 0.9f, 1.0f};

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(c -> {
			c.options.renderDistance().set(6);
		});
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			sp.getConnection().waitForChunksDownload();
			TestServerContext server = sp.getServer();
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("gamemode creative");

			final int[] origin = new int[3];
			server.runOnServer(s -> {
				ServerLevel level = s.overworld();
				BlockPos p = sp.getConnection().getServerPlayer().blockPosition().above(40);
				origin[0] = p.getX();
				origin[1] = p.getY();
				origin[2] = p.getZ();
				for (int dx = -10; dx <= 10; dx++) {
					for (int dz = -10; dz <= 10; dz++) {
						level.setBlockAndUpdate(p.offset(dx, -1, dz), Blocks.GRASS_BLOCK.defaultBlockState());
					}
				}
				FakeMember.setMeltFrozen(true);
				FakeMember m = ModEntities.FAKE_MEMBER.create(level, EntitySpawnReason.COMMAND);
				m.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0f, 0f);
				m.setSkinName("MHF_Steve");
				m.setDisplayNameText("Melt");
				m.setNoAi(true);
				level.addFreshEntity(m);
				m.autopilot().setEnabled(false);
			});
			// 正面(南側)3.5 ブロックから北向きに見る
			server.runCommand(String.format(Locale.ROOT, "execute as @a run tp @s %d.5 %d %d.5 180 8",
					origin[0], origin[1], origin[2] + 4));
			context.waitTicks(40);
			sp.getConnection().waitForChunksRender();

			// スキンが解決されて既定スキンと変わるまで最大 ~30 秒待つ（CI からセッションサーバーに届かなければ変わらない）
			String skinInfo = "";
			for (int i = 0; i < 30; i++) {
				skinInfo = context.computeOnClient(c -> {
					for (Entity e : c.level.entitiesForRendering()) {
						if (e instanceof FakeMember f) {
							var id = com.amaguri.aibuddy.client.SkinColors.skinOf(f).body().texturePath();
							boolean def = id.equals(net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin().body().texturePath());
							return "skin=" + id + " default=" + def + " profile=" + f.getProfile().name();
						}
					}
					return "no member";
				});
				if (skinInfo.contains("default=false")) {
					break;
				}
				context.waitTicks(20);
			}
			LOGGER.info("[skincheck] {}", skinInfo);

			StringBuilder bad = new StringBuilder();
			for (float m : STEPS) {
				server.runCommand("aibuddy melt " + m);
				context.waitTicks(40);
				float client = context.computeOnClient(c -> {
					for (Entity e : c.level.entitiesForRendering()) {
						if (e instanceof FakeMember f) {
							return f.getMelt();
						}
					}
					return -1f;
				});
				LOGGER.info("[meltshot] server melt={} client melt={}", m, client);
				if (Math.abs(client - m) > 0.01f) {
					bad.append(String.format(Locale.ROOT, " m=%.2f client=%.2f;", m, client));
				}
				context.takeScreenshot(TestScreenshotOptions.of(String.format(Locale.ROOT, "melt_%03d", Math.round(m * 100))).disableCounterPrefix());
			}
			if (bad.length() > 0) {
				throw new AssertionError("溶けがクライアントに同期されていない:" + bad);
			}
		}
	}
}
