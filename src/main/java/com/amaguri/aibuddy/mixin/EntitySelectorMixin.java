package com.amaguri.aibuddy.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;

/**
 * `/tp Luzaku @s` のような素のプレイヤー名指定で、同名の本物プレイヤーがいなければ FakeMember を返す。
 * findSingleEntity も内部で findEntities を使う（UNVERIFIED: 本体は apidump に無いのでバイトコード未確認）。
 */
@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
	@Shadow
	@Final
	private String playerName;

	@Inject(method = "findEntities", at = @At("RETURN"), cancellable = true)
	private void aibuddy$fakeMemberByName(CommandSourceStack source, CallbackInfoReturnable<List<? extends Entity>> cir) {
		if (this.playerName == null || !cir.getReturnValue().isEmpty()) {
			return;
		}
		for (ServerLevel level : source.getServer().getAllLevels()) {
			for (FakeMember m : level.getEntities(ModEntities.FAKE_MEMBER, e -> e.isAlive())) {
				if (m.getDisplayNameText().equalsIgnoreCase(this.playerName)) {
					List<Entity> out = new ArrayList<>(1);
					out.add(m);
					cir.setReturnValue(out);
					return;
				}
			}
		}
	}
}
