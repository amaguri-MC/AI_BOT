package com.amaguri.aibuddy.client;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** AvatarRenderState に溶け具合と水たまりの色を足したもの。レンダラの S は AvatarRenderState のまま使う(PlayerModel の型の都合)。 */
public class FakeMemberRenderState extends AvatarRenderState {
	/** 0..1 */
	public float melt;
	/** 水たまりの色 0xRRGGBB（スキンの胴の平均色を少し暗くしたもの） */
	public int puddleRgb = 0x406070;
}
