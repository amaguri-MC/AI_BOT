package com.amaguri.aibuddy.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerSkin;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;

public class AiBuddyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(ModEntities.FAKE_MEMBER, FakeMemberRenderer::new);
		ClientTickEvents.END_LEVEL_TICK.register(AiBuddyClient::spawnDrips);
	}

	/** 溶けが 0.2 を超えた FakeMember の体から、スキンの色の滴を落とす。 */
	private static void spawnDrips(ClientLevel level) {
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof FakeMember f)) {
				continue;
			}
			float m = f.getMelt();
			if (m <= 0.2f || f.isPuddle()) {
				continue;
			}
			var rnd = f.getRandom();
			// 本数は溶けに比例（0.2 -> 約0.1個/tick、0.99 -> 約0.6個/tick）
			if (rnd.nextFloat() > 0.1f + 0.5f * (m - 0.2f) / 0.8f) {
				continue;
			}
			PlayerSkin skin = SkinColors.skinOf(f);
			int rgb = SkinColors.puddleRgb(skin);
			double spread = 0.25 + 0.4 * m;
			double x = f.getX() + (rnd.nextDouble() - 0.5) * 2.0 * spread;
			double z = f.getZ() + (rnd.nextDouble() - 0.5) * 2.0 * spread;
			double y = f.getY() + 0.1 + rnd.nextDouble() * 1.7 * MeltDeform.heightScale(m);
			// UNVERIFIED: DUST パーティクルが渡した速度で落ちること（落ちなければ ParticleTypes.FALLING_* に差し替える）
			level.addParticle(new DustParticleOptions(rgb, 0.7f), x, y, z, 0.0, -0.06, 0.0);
		}
	}
}
