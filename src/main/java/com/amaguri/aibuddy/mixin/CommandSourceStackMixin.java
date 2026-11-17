package com.amaguri.aibuddy.mixin;

import java.util.ArrayList;
import java.util.Collection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;

import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.entity.ModEntities;

/** タブ補完（プレイヤー名候補）に FakeMember の名前も出す。 */
@Mixin(CommandSourceStack.class)
public abstract class CommandSourceStackMixin {
	@Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true)
	private void aibuddy$addFakeMemberNames(CallbackInfoReturnable<Collection<String>> cir) {
		CommandSourceStack self = (CommandSourceStack) (Object) this;
		Collection<String> names = new ArrayList<>(cir.getReturnValue());
		for (ServerLevel level : self.getServer().getAllLevels()) {
			for (FakeMember m : level.getEntities(ModEntities.FAKE_MEMBER, e -> true)) {
				String n = m.getDisplayNameText();
				if (!n.isEmpty() && !names.contains(n)) {
					names.add(n);
				}
			}
		}
		cir.setReturnValue(names);
	}
}
