package com.amaguri.aibuddy.skill;

import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * ブロックを1つずつ掘る。速さはダイヤのツルハシ相当（tick = 硬さ*30/8）。
 * 腕を振り、破壊の進行表示（ひび割れ）を出し、壊したら正しい道具でのドロップを足元に落とす。
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
		total = Math.max(2, (int) Math.ceil(h * 30.0f / 8.0f));
		lastStage = -1;
		o.skills().hold(Items.DIAMOND_PICKAXE);
		return true;
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
		// UNVERIFIED: Block.getDrops 6引数版（Entity, ItemInstance）の意味は javap の型だけで確認。
		// ダイヤのツルハシで掘った扱いでドロップを計算（素手だと鉱石が落ちないため）
		List<ItemStack> drops = Block.getDrops(s, l, pos, be, o, new ItemStack(Items.DIAMOND_PICKAXE));
		l.destroyBlock(pos, false, o);
		for (ItemStack d : drops) {
			Block.popResource(l, pos, d);
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
