package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * ブロックを1つずつ掘る。バニラのプレイヤーと同じ式:
 * 1tickの進み = 道具の速さ / 硬さ / (適正道具なら30、そうでなければ100)。合計1.0で壊れる。
 * 持ち物から一番速い道具を手に持ち、壊したら耐久を減らす。適正道具でなければドロップ無し。
 * 腕を振り、破壊の進行表示（ひび割れ）を出す。
 */
final class Digger {
	static final int PROGRESS = 0;
	static final int BROKEN = 1;
	static final int ABORTED = 2;

	private final FakeMember o;
	private BlockPos pos;
	private int ticks;
	private int total;
	private int lastStage = -1;
	private ItemStack tool = ItemStack.EMPTY;
	private boolean correct;
	private float hardness;

	Digger(FakeMember o) {
		this.o = o;
	}

	boolean active() {
		return pos != null;
	}

	BlockPos pos() {
		return pos;
	}

	/** 掘れないブロック（空気・岩盤など）なら false */
	boolean begin(BlockPos p) {
		cancel();
		ServerLevel l = SkillUtil.level(o);
		BlockState s = l.getBlockState(p);
		if (s.isAir()) {
			return false;
		}
		float h = s.getDestroySpeed(l, p);
		if (h < 0) {
			return false;
		}
		pos = p.immutable();
		ticks = 0;
		lastStage = -1;
		tool = Inv.bestTool(o, s);
		Inv.equip(o, tool);
		correct = !s.requiresCorrectToolForDrops() || (!tool.isEmpty() && tool.isCorrectToolForDrops(s));
		hardness = h;
		total = ticksFor(s, h, tool, correct);
		return true;
	}

	/** バニラ式の破壊tick数（Player.getDestroySpeed と BlockBehaviour.getDestroyProgress 相当）。 */
	private int ticksFor(BlockState s, float hardness, ItemStack t, boolean ok) {
		if (hardness == 0.0f) {
			return 1;
		}
		float speed = t.isEmpty() ? 1.0f : t.getDestroySpeed(s);
		// UNVERIFIED: 水中（水中採掘なし）と空中は /5（バニラのプレイヤーと同じ）。水中呼吸系エンチャントは考えない。
		if (o.isEyeInFluid(FluidTags.WATER)) {
			speed /= 5.0f;
		}
		if (!o.onGround()) {
			speed /= 5.0f;
		}
		float perTick = speed / hardness / (ok ? 30.0f : 100.0f);
		if (perTick >= 1.0f) {
			return 1;
		}
		return Math.max(1, (int) Math.ceil(1.0f / perTick));
	}

	/** 今の道具で壊すと何tickかかるか（計画用）。 */
	int estimate(BlockPos p) {
		ServerLevel l = SkillUtil.level(o);
		BlockState s = l.getBlockState(p);
		float h = s.getDestroySpeed(l, p);
		if (s.isAir() || h < 0) {
			return -1;
		}
		ItemStack t = Inv.bestTool(o, s);
		boolean ok = !s.requiresCorrectToolForDrops() || (!t.isEmpty() && t.isCorrectToolForDrops(s));
		return ticksFor(s, h, t, ok);
	}

	int tick() {
		if (pos == null) {
			return ABORTED;
		}
		ServerLevel l = SkillUtil.level(o);
		BlockState s = l.getBlockState(pos);
		if (s.isAir()) {
			cancel();
			return BROKEN;
		}
		Vec3 c = Vec3.atCenterOf(pos);
		if (o.getEyePosition().distanceToSqr(c) > 7.0 * 7.0) {
			cancel();
			return ABORTED;
		}
		SkillUtil.lookAt(o, c);
		if (o.getMainHandItem() != tool) {
			Inv.equip(o, tool);
		}
		if (ticks % 5 == 0) {
			SkillUtil.swing(o);
		}
		if (ticks % 4 == 0) {
			l.playSound(null, pos, s.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6f, 0.8f);
		}
		ticks++;
		int stage = Math.min(9, (int) (ticks * 10.0f / total));
		if (stage != lastStage) {
			l.destroyBlockProgress(o.getId(), pos, stage);
			lastStage = stage;
		}
		if (ticks < total) {
			return PROGRESS;
		}
		BlockEntity be = l.getBlockEntity(pos);
		// UNVERIFIED: Block.getDrops 6引数版（Entity, ItemInstance）。実際の道具を渡す。適正道具でなければドロップ無し（バニラと同じ）。
		List<ItemStack> drops = correct ? Block.getDrops(s, l, pos, be, o, tool.isEmpty() ? ItemStack.EMPTY : tool) : List.of();
		l.destroyBlock(pos, false, o);
		for (ItemStack d : drops) {
			Block.popResource(l, pos, d);
		}
		if (!tool.isEmpty() && hardness > 0.0f) {
			Inv.damageTool(o, tool, 1);
		}
		l.destroyBlockProgress(o.getId(), pos, -1);
		pos = null;
		return BROKEN;
	}

	void cancel() {
		if (pos != null) {
			SkillUtil.level(o).destroyBlockProgress(o.getId(), pos, -1);
			pos = null;
		}
	}
}
