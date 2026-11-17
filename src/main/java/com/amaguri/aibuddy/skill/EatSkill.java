package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/** 食べ物を1つ食べて体力を回復する。 */
final class EatSkill extends Skill {
	private int slot = -1;
	private FoodProperties food;
	private int ticks;
	private boolean usedStart;

	EatSkill(FakeMember o, SkillRunner r) {
		super(o, r);
	}

	static boolean isFood(ItemStack st) {
		return !st.isEmpty() && st.get(DataComponents.FOOD) != null;
	}

	@Override
	String begin() {
		int bestNutrition = -1;
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack st = o.getInventory().getItem(i);
			if (isFood(st)) {
				FoodProperties f = st.get(DataComponents.FOOD);
				if (f.nutrition() > bestNutrition) {
					bestNutrition = f.nutrition();
					slot = i;
					food = f;
				}
			}
		}
		if (slot < 0) {
			return "できない: 食べ物を持っていない";
		}
		ItemStack st = o.getInventory().getItem(slot);
		runner.holdStack(st.copyWithCount(1));
		// UNVERIFIED: Mob に startUsingItem して腕を上げる表示を出す。効果は自前で適用するので、
		// 標準の消費で手持ち（見た目用コピー）が減るだけ。
		o.startUsingItem(InteractionHand.MAIN_HAND);
		usedStart = o.isUsingItem();
		return null;
	}

	@Override
	void tick() {
		ticks++;
		if (!usedStart && ticks % 6 == 0) {
			SkillUtil.swing(o);
		}
		if (ticks >= 34) {
			o.stopUsingItem();
			ItemStack st = o.getInventory().getItem(slot);
			if (!isFood(st)) {
				finish("食べ損ねた", null);
				return;
			}
			st.shrink(1);
			o.getInventory().setItem(slot, st);
			o.heal(food.nutrition());
			finish("食べた（体力" + Math.round(o.getHealth()) + "/" + Math.round(o.getMaxHealth()) + "）", null);
		}
	}

	@Override
	void onStop() {
		super.onStop();
		o.stopUsingItem();
	}

	@Override
	String status() {
		return "食べている";
	}
}
