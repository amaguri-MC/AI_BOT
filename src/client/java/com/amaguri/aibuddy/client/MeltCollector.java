package com.amaguri.aibuddy.client;

import java.util.List;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * LivingEntityRenderer.submit に渡す SubmitNodeCollector の包み。
 * 本体モデルの submitModel だけを submitCustomGeometry に差し替え、溶けの頂点変形を挟む。
 * それ以外（名前タグ・影・手持ちアイテムなど）はそのまま素通しする。
 *
 * 描画は後でまとめて行われるため、コールバック内で model.resetPose()/setupAnim(state) をやり直す
 * （ModelFeatureRenderer#prepareModel が同じことをしている）。
 */
final class MeltCollector implements SubmitNodeCollector {
	private final OrderedSubmitNodeCollector inner;
	private final SubmitNodeCollector root;
	private final Model<?> target;
	private final Object targetState;
	private final float melt;

	MeltCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector inner, Model<?> target, Object targetState, float melt) {
		this.root = root;
		this.inner = inner;
		this.target = target;
		this.targetState = targetState;
		this.melt = melt;
	}

	static MeltCollector wrap(SubmitNodeCollector root, Model<?> target, Object targetState, float melt) {
		return new MeltCollector(root, root, target, targetState, melt);
	}

	@Override
	public OrderedSubmitNodeCollector order(int order) {
		return new MeltCollector(root, root.order(order), target, targetState, melt);
	}

	@Override
	public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay, int tint,
			TextureAtlasSprite sprite, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling) {
		if (model == target && state == targetState) {
			float time = state instanceof EntityRenderState es ? es.ageInTicks : 0f;
			inner.submitCustomGeometry(poseStack, renderType, (pose, consumer) -> {
				model.resetPose();
				model.setupAnim(state);
				MeltVertexConsumer wrapped = new MeltVertexConsumer(consumer, pose, melt, time);
				model.renderToBuffer(new PoseStack(), wrapped, light, overlay, tint);
				wrapped.finish();
			});
			return;
		}
		inner.submitModel(model, state, poseStack, renderType, light, overlay, tint, sprite, outlineColor, crumbling);
	}

	@Override
	public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
		inner.submitShadow(poseStack, radius, pieces);
	}

	@Override
	public void submitNameTag(PoseStack poseStack, Vec3 pos, int offsetY, Component text, boolean seeThrough, int light, CameraRenderState camera) {
		inner.submitNameTag(poseStack, pos, offsetY, text, seeThrough, light, camera);
	}

	@Override
	public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean dropShadow, Font.DisplayMode mode, int light,
			int color, int background, int outline) {
		inner.submitText(poseStack, x, y, text, dropShadow, mode, light, color, background, outline);
	}

	@Override
	public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
		inner.submitFlame(poseStack, state, rotation);
	}

	@Override
	public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
		inner.submitLeash(poseStack, leash);
	}

	@Override
	public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int light) {
		inner.submitMovingBlock(poseStack, state, light);
	}

	@Override
	public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tints, int light, int overlay,
			int outline) {
		inner.submitBlockModel(poseStack, renderType, parts, tints, light, overlay, outline);
	}

	@Override
	public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {
		inner.submitBreakingBlockModel(poseStack, parts, progress);
	}

	@Override
	public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean translucent) {
		inner.submitShapeOutline(poseStack, shape, renderType, color, width, translucent);
	}

	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext context, int light, int overlay, int outline, int[] tints, List<BakedQuad> quads,
			ItemStackRenderState.FoilType foil) {
		inner.submitItem(poseStack, context, light, overlay, outline, tints, quads, foil);
	}

	@Override
	public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
		inner.submitCustomGeometry(poseStack, renderType, renderer);
	}

	@Override
	public void submitQuadParticleGroup(QuadParticleRenderState state) {
		inner.submitQuadParticleGroup(state);
	}

	@Override
	public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean flag) {
		inner.submitGizmoPrimitives(group, camera, flag);
	}
}
