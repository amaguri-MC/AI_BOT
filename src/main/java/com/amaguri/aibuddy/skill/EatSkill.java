package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 食べ物を1つ食べて体力を回復する。 */
final class EatSkill extends Skill {
	private int slot = -1;
	private FoodProperties food;
	private int ticks;
	private boolean usedStart;
	private ItemStack savedHand = ItemStack.EMPTY;
	private boolean holding;

	EatSkill(FakeMember o, SkillRunner r) {
		super(o, r);
	}

	static boolean isFood(ItemStack st) {
		return !st.isEmpty() && st.get(DataComponents.FOOD) != null;
	}

	/** 体に悪い・効果が変わる物は自動では食べない（腐った肉、毒ジャガイモ、フグ、クモの目、紫コーラスフルーツなど）。 */
	private static boolean avoid(ItemStack st) {
		return st.is(Items.ROTTEN_FLESH) || st.is(Items.POISONOUS_POTATO) || st.is(Items.PUFFERFISH) || st.is(Items.SPIDER_EYE)
				|| st.is(Items.CHICKEN) || st.is(Items.CHORUS_FRUIT) || st.is(Items.SUSPICIOUS_STEW) || st.is(Items.GOLDEN_APPLE)
				|| st.is(Items.ENCHANTED_GOLDEN_APPLE);
	}

	@Override
	String begin() {
		int bestNutrition = -1;
		for (int i = 0; i < o.getInventory().getContainerSize(); i++) {
			ItemStack st = o.getInventory().getItem(i);
			if (isFood(st) && !avoid(st)) {
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
		// 見た目用に食べ物を手に持つ（実物は持ち物のまま。効果は自前で適用する）。終わったら元の手持ちに戻す。
		savedHand = o.getMainHandItem();
		holding = true;
		Inv.equip(o, st.copyWithCount(1));
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
		if (holding) {
			holding = false;
			Inv.equip(o, savedHand);
		}
	}

	@Override
	String status() {
		return "食べている";
	}
}
