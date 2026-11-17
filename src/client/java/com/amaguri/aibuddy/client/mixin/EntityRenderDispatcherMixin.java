package com.amaguri.aibuddy.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import com.amaguri.aibuddy.client.FakeMemberRenderState;
import com.amaguri.aibuddy.client.FakeMemberRenderer;

/**
 * 26.2 の EntityRenderDispatcher は「描画状態」から描画器を選び、AvatarRenderState 系はプレイヤー用の AvatarRenderer に回す。
 * FakeMember の状態(FakeMemberRenderState)は自分の描画器に回し直す。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "getRenderer(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
			at = @At("HEAD"), cancellable = true)
	private void aibuddy$fakeMemberRenderer(EntityRenderState state, CallbackInfoReturnable<EntityRenderer<?, ?>> cir) {
		if (state instanceof FakeMemberRenderState && FakeMemberRenderer.INSTANCE != null) {
			cir.setReturnValue(FakeMemberRenderer.INSTANCE);
		}
	}
}
