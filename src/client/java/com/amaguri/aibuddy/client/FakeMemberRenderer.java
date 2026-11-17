package com.amaguri.aibuddy.client;

import com.amaguri.aibuddy.AiBuddyMod;
import com.amaguri.aibuddy.entity.FakeMember;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * FakeMember を本人のスキンのプレイヤー姿で描く。溶けは {@link MeltCollector} が本体モデルの頂点を変形して表す。
 * 足元の水たまりはここで描く。m=0 のときは通常のプレイヤーと同じ経路（変形も水たまりも無し）。
 */
public class FakeMemberRenderer extends LivingEntityRenderer<FakeMember, AvatarRenderState, PlayerModel> {
	private static final Identifier PUDDLE_TEXTURE = AiBuddyMod.id("textures/entity/puddle.png");
	/** 溶けがこれ以上なら手持ちアイテムは描かない（腕の位置が変形後の体とずれるため）。 */
	private static final float LAYERS_MELT_LIMIT = 0.25f;

	/** EntityRenderDispatcherMixin が参照する。 */
	public static FakeMemberRenderer INSTANCE;
	private final PlayerModel wideModel;
	private final PlayerModel slimModel;

	public FakeMemberRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
		this.wideModel = this.model;
		this.slimModel = new PlayerModel(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true);
		this.addLayer(new PlayerItemInHandLayer<>(this));
		INSTANCE = this;
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new FakeMemberRenderState();
	}

	@Override
	public Identifier getTextureLocation(AvatarRenderState state) {
		return state.skin.body().texturePath();
	}

	@Override
	public void extractRenderState(FakeMember entity, AvatarRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);

		PlayerSkin skin = SkinColors.skinOf(entity.getSkinName());
		state.skin = skin;
		state.id = entity.getId();
		state.showHat = true;
		state.showJacket = true;
		state.showLeftPants = true;
		state.showRightPants = true;
		state.showLeftSleeve = true;
		state.showRightSleeve = true;
		state.showCape = false;
		state.rightArmPose = armPose(entity.getMainHandItem());
		state.leftArmPose = armPose(entity.getOffhandItem());

		float melt = Math.max(0f, Math.min(1f, entity.getMelt()));
		if (state instanceof FakeMemberRenderState fs) {
			fs.melt = melt;
			// 胴の平均色はテクスチャID単位でキャッシュ済み（毎フレームは Map 引きだけ）
			fs.puddleRgb = melt > 0f ? SkinColors.puddleRgb(skin) : fs.puddleRgb;
		}
		if (melt > 0f && state.nameTagAttachment != null) {
			Vec3 a = state.nameTagAttachment;
			state.nameTagAttachment = new Vec3(a.x, 0.3 + (a.y - 0.3) * MeltDeform.heightScale(melt), a.z);
		}
	}

	private static HumanoidModel.ArmPose armPose(ItemStack stack) {
		// UNVERIFIED: 弓や盾などの構えは出さず、持っていれば ITEM(腕を前に出す)、無ければ EMPTY にしている
		return stack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
	}

	private static float meltOf(AvatarRenderState state) {
		return state instanceof FakeMemberRenderState fs ? fs.melt : 0f;
	}

	@Override
	protected void scale(AvatarRenderState state, PoseStack poseStack) {
		poseStack.scale(0.9375f, 0.9375f, 0.9375f); // AvatarRenderer と同じ
	}

	@Override
	protected boolean shouldRenderLayers(AvatarRenderState state) {
		return meltOf(state) < LAYERS_MELT_LIMIT && super.shouldRenderLayers(state);
	}

	@Override
	protected float getShadowRadius(AvatarRenderState state) {
		return meltOf(state) > 0.3f ? 0f : super.getShadowRadius(state);
	}

	@Override
	protected Component getNameTag(FakeMember entity) {
		return Component.literal(entity.getDisplayNameText());
	}

	@Override
	protected boolean shouldShowName(FakeMember entity, double distanceSq) {
		return distanceSq < 64.0 * 64.0 && !entity.isInvisible();
	}

	@Override
	public void submit(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// 太い腕/細い腕のモデルは submit 時に選ぶ（extract は全員分が先に走るため）
		this.model = state.skin.model() == PlayerModelType.SLIM ? this.slimModel : this.wideModel;
		float m = meltOf(state);
		if (m <= 0f) {
			super.submit(state, poseStack, collector, camera);
			return;
		}
		submitPuddle(state, poseStack, collector, m);
		super.submit(state, poseStack, MeltCollector.wrap(collector, this.model, state, m), camera);
	}

	/** 足元の不規則な円盤。半径 0.3+0.9m、胴の平均色を暗くして半透明。 */
	private void submitPuddle(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector collector, float m) {
		float alpha = 0.8f * MeltDeform.smooth(0f, 0.25f, m);
		if (alpha <= 0.01f) {
			return;
		}
		int rgb = state instanceof FakeMemberRenderState fs ? fs.puddleRgb : 0x406070;
		int argb = ((int) (alpha * 255f) << 24) | (rgb & 0xFFFFFF);
		float radius = 0.3f + 0.9f * m;
		float phase = (state.id % 97) * 0.37f;
		float time = state.ageInTicks;
		int light = state.lightCoords;
		// UNVERIFIED: entityTranslucent(Identifier) が白テクスチャ×頂点色で半透明に描けること
		collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucent(PUDDLE_TEXTURE), (pose, consumer) -> {
			final int n = 28;
			int rim = rimColor(argb);
			float prevX = 0;
			float prevZ = 0;
			for (int i = 0; i <= n; i++) {
				double th = 2.0 * Math.PI * i / n;
				double wob = 1.0 + 0.12 * Math.sin(3 * th + phase) + 0.07 * Math.sin(5 * th + 2 * phase) + 0.02 * Math.sin(time * 0.1 + 4 * th);
				float x = (float) (Math.cos(th) * radius * wob);
				float z = (float) (Math.sin(th) * radius * wob);
				if (i > 0) {
					float ix = x * 0.82f;
					float iz = z * 0.82f;
					float px = prevX * 0.82f;
					float pz = prevZ * 0.82f;
					// 中央は胴の平均色(暗め)、縁(外側18%)は少し明るく。上向きの1枚だけ出す（両面を重ねると同じ高さで Z ファイトして縞になる。26.2 の entityTranslucent は裏面を消さないので1枚でよい）
					quad(consumer, pose, 0, 0, ix, iz, px, pz, px, pz, argb, light, 1f);
					quad(consumer, pose, ix, iz, x, z, prevX, prevZ, px, pz, rim, light, 1f);
				}
				prevX = x;
				prevZ = z;
			}
		});
	}

	/** 縁用: 色を少し明るく、アルファを少し上げる。 */
	private static int rimColor(int argb) {
		int a = Math.min(255, ((argb >>> 24) & 0xFF) + 25);
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * 1.25f) + 8);
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * 1.25f) + 8);
		int b = Math.min(255, (int) ((argb & 0xFF) * 1.25f) + 8);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static void quad(VertexConsumer c, PoseStack.Pose pose, float x0, float z0, float x1, float z1,
			float x2, float z2, float x3, float z3, int argb, int light, float ny) {
		float y = 0.02f;
		vertex(c, pose, x0, y, z0, argb, light, ny);
		vertex(c, pose, x1, y, z1, argb, light, ny);
		vertex(c, pose, x2, y, z2, argb, light, ny);
		vertex(c, pose, x3, y, z3, argb, light, ny);
	}

	private static void vertex(VertexConsumer c, PoseStack.Pose pose, float x, float y, float z, int argb, int light,
			float ny) {
		c.addVertex(pose, x, y, z).setColor(argb).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0f, ny, 0f);
	}
}
