package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.List;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 足元の少し前に黒曜石の枠（外寸4x5、角なし）を1ブロックずつ腕を振りながら置いて着火する。
 * 手持ちの黒曜石が足りなくても置く（演出優先）。
 */
final class PortalSkill extends Skill {
	private static final int CLEAR = 0;
	private static final int PLACE = 1;
	private static final int IGNITE = 2;
	private static final int VERIFY = 3;

	private record Step(int kind, BlockPos pos) {
	}

	private final List<Step> steps = new ArrayList<>();
	private int index;
	private int delay;
	private int walkTicks;
	private boolean walking = true;
	private Direction.Axis axis;
	private BlockPos anchor; // 内側の左下
	private BlockPos[] inner;
	private int placed;

	PortalSkill(FakeMember o, SkillRunner r) {
		super(o, r);
	}

	@Override
	String begin() {
		ServerLevel l = SkillUtil.level(o);
		if (l.dimension().equals(Level.END)) {
			return "できない: エンドではネザーゲートは作れない";
		}
		Direction d = Direction.fromYRot(o.getYRot());
		BlockPos feet = o.blockPosition();
		anchor = feet.relative(d, 3);
		axis = d.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
		Direction u = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
		// 周りを片付ける（内側と枠）
		List<BlockPos> frame = new ArrayList<>();
		inner = new BlockPos[6];
		int k = 0;
		for (int a = 0; a < 4; a++) {
			for (int b = 0; b < 5; b++) {
				BlockPos p = anchor.relative(u, a - 1).above(b - 1);
				boolean corner = (a == 0 || a == 3) && (b == 0 || b == 4);
				boolean isFrame = a == 0 || a == 3 || b == 0 || b == 4;
				if (corner) {
					continue;
				}
				if (isFrame) {
					frame.add(p);
				} else {
					inner[k++] = p;
				}
			}
		}
		for (BlockPos p : inner) {
			steps.add(new Step(CLEAR, p));
		}
		// 左柱（下から上）→ 上辺 → 右柱（上から下）→ 下辺 の順に置く
		List<BlockPos> order = new ArrayList<>();
		for (int b = 0; b < 5; b++) {
			add(order, frame, anchor.relative(u, -1).above(b - 1));
		}
		for (int a = 0; a < 4; a++) {
			add(order, frame, anchor.relative(u, a - 1).above(3));
		}
		for (int b = 3; b >= 0; b--) {
			add(order, frame, anchor.relative(u, 2).above(b - 1));
		}
		for (int a = 3; a >= 0; a--) {
			add(order, frame, anchor.relative(u, a - 1).above(-1));
		}
		for (BlockPos p : order) {
			steps.add(new Step(CLEAR, p));
			steps.add(new Step(PLACE, p));
		}
		steps.add(new Step(IGNITE, inner[0]));
		steps.add(new Step(VERIFY, inner[0]));
		// 枠の中心が見える位置（手前3マス）に立つ
		Vec3 stand = Vec3.atBottomCenterOf(anchor.relative(d.getOpposite(), 1));
		nav.go(stand, 1.5, 1.0);
		runner.hold(Items.OBSIDIAN);
		return null;
	}

	private static void add(List<BlockPos> order, List<BlockPos> frame, BlockPos p) {
		if (frame.contains(p) && !order.contains(p)) {
			order.add(p);
		}
	}

	@Override
	void tick() {
		ServerLevel l = SkillUtil.level(o);
		if (walking) {
			if (!nav.arrived() && !nav.failed() && ++walkTicks < 200) {
				nav.tick();
				return;
			}
			walking = false;
			nav.stop();
		}
		if (index >= steps.size()) {
			return;
		}
		if (delay > 0) {
			delay--;
			return;
		}
		Step s = steps.get(index);
		Vec3 c = Vec3.atCenterOf(s.pos());
		SkillUtil.lookAt(o, c);
		switch (s.kind()) {
			case CLEAR -> {
				BlockState st = l.getBlockState(s.pos());
				if (!st.isAir() && st.getDestroySpeed(l, s.pos()) >= 0) {
					SkillUtil.swing(o);
					l.destroyBlock(s.pos(), true, o);
					delay = 3;
				}
				index++;
			}
			case PLACE -> {
				SkillUtil.swing(o);
				if (o.getInventory().countItem(Items.OBSIDIAN) > 0) {
					o.getInventory().removeItemType(Items.OBSIDIAN, 1);
				}
				BlockState ob = Blocks.OBSIDIAN.defaultBlockState();
				l.setBlock(s.pos(), ob, 3);
				l.playSound(null, s.pos(), ob.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 0.9f);
				placed++;
				delay = 6;
				index++;
			}
			case IGNITE -> {
				runner.hold(Items.FLINT_AND_STEEL);
				SkillUtil.swing(o);
				l.playSound(null, s.pos(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
				// UNVERIFIED: 火を置くと BaseFireBlock が枠を認識してゲートを張る挙動（26.2で変わっていないか未確認）。
				// 張られなければ VERIFY で直接ゲートブロックを置く。
				l.setBlock(s.pos(), Blocks.FIRE.defaultBlockState(), 3);
				delay = 15;
				index++;
			}
			case VERIFY -> {
				// 自動でゲートにならなかった場合は内側を直接ゲートで埋める
				if (!l.getBlockState(inner[0]).is(Blocks.NETHER_PORTAL)) {
					BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);
					for (BlockPos p : inner) {
						l.setBlock(p, portal, 18);
					}
				}
				String where = SkillUtil.pos(inner[0]);
				finish("ネザーゲートを作った " + where, "ネザーゲートを作った 座標 " + where);
			}
			default -> index++;
		}
	}

	@Override
	String status() {
		return "ネザーゲートを作っている（黒曜石" + placed + "/10）";
	}
}
