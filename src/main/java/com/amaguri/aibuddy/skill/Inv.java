package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.block.state.BlockState;

/** FakeMember.getInventory() の操作をここに集約する（他担当も使う）。 */
final class Inv {
	private Inv() {
	}

	static int count(FakeMember o, Item i) {
		int n = 0;
		SimpleContainer c = o.getInventory();
		for (int k = 0; k < c.getContainerSize(); k++) {
			ItemStack st = c.getItem(k);
			if (!st.isEmpty() && st.is(i)) {
				n += st.getCount();
			}
		}
		return n;
	}

	static int count(FakeMember o, TagKey<Item> t) {
		int n = 0;
		SimpleContainer c = o.getInventory();
		for (int k = 0; k < c.getContainerSize(); k++) {
			ItemStack st = c.getItem(k);
			if (!st.isEmpty() && st.is(t)) {
				n += st.getCount();
			}
		}
		return n;
	}

	/** n 個そろっていれば消費して true。足りなければ何も減らさず false。 */
	static boolean remove(FakeMember o, Item i, int n) {
		if (n <= 0) {
			return true;
		}
		if (count(o, i) < n) {
			return false;
		}
		takeWhere(o, n, st -> st.is(i));
		return true;
	}

	static boolean remove(FakeMember o, TagKey<Item> t, int n) {
		if (n <= 0) {
			return true;
		}
		if (count(o, t) < n) {
			return false;
		}
		takeWhere(o, n, st -> st.is(t));
		return true;
	}

	private static void takeWhere(FakeMember o, int n, java.util.function.Predicate<ItemStack> p) {
		SimpleContainer c = o.getInventory();
		// 手に持っている物は最後に減らす（持ち替えを減らす）
		for (int k = 0; k < c.getContainerSize() && n > 0; k++) {
			ItemStack st = c.getItem(k);
			if (st.isEmpty() || !p.test(st)) {
				continue;
			}
			int t = Math.min(n, st.getCount());
			st.shrink(t);
			n -= t;
			if (st.isEmpty()) {
				c.setItem(k, ItemStack.EMPTY);
			}
		}
		c.setChanged();
	}

	/** 持ち物に足す。入らない分は足元に落とす。 */
	static void add(FakeMember o, ItemStack s) {
		if (s == null || s.isEmpty()) {
			return;
		}
		ItemStack left = o.getInventory().addItem(s.copy());
		if (!left.isEmpty() && o.level() instanceof ServerLevel sl) {
			ItemEntity ie = o.spawnAtLocation(sl, left);
			if (ie != null) {
				ie.setDefaultPickUpDelay();
			}
		}
	}

	/** 道具として一番速いもの。素手より速い物が無ければ EMPTY。適正道具が必要なブロックは適正な物だけ選ぶ。 */
	static ItemStack bestTool(FakeMember o, BlockState s) {
		SimpleContainer c = o.getInventory();
		boolean need = s.requiresCorrectToolForDrops();
		ItemStack best = ItemStack.EMPTY;
		float bestSpeed = 1.0f;
		for (int k = 0; k < c.getContainerSize(); k++) {
			ItemStack st = c.getItem(k);
			if (st.isEmpty()) {
				continue;
			}
			if (need && !st.isCorrectToolForDrops(s)) {
				continue;
			}
			float sp = st.getDestroySpeed(s);
			// 壊れかけの道具は少し不利にして、同じ速さなら傷んだ方を先に使う
			if (sp > bestSpeed + 0.001f || (best.isEmpty() && sp > 1.0f)) {
				best = st;
				bestSpeed = sp;
			} else if (!best.isEmpty() && Math.abs(sp - bestSpeed) < 0.001f && st.getDamageValue() > best.getDamageValue()) {
				best = st;
			}
		}
		// 適正道具が要るブロックで、速さ1の適正道具（例: 剣でクモの巣以外）は選ばれない。素手でも掘れる物だけ素手。
		if (best.isEmpty() && need) {
			for (int k = 0; k < c.getContainerSize(); k++) {
				ItemStack st = c.getItem(k);
				if (!st.isEmpty() && st.isCorrectToolForDrops(s)) {
					return st;
				}
			}
		}
		return best;
	}

	/** 一番強い武器。無ければ EMPTY。 */
	static ItemStack bestWeapon(FakeMember o) {
		SimpleContainer c = o.getInventory();
		ItemStack best = ItemStack.EMPTY;
		int bestScore = 0;
		for (int k = 0; k < c.getContainerSize(); k++) {
			ItemStack st = c.getItem(k);
			if (st.isEmpty()) {
				continue;
			}
			int sc = weaponScore(st);
			if (sc > bestScore) {
				bestScore = sc;
				best = st;
			}
		}
		return best;
	}

	private static int weaponScore(ItemStack st) {
		String p = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
		int tier = 0;
		if (p.startsWith("netherite_")) {
			tier = 5;
		} else if (p.startsWith("diamond_")) {
			tier = 4;
		} else if (p.startsWith("iron_")) {
			tier = 3;
		} else if (p.startsWith("stone_")) {
			tier = 2;
		} else if (p.startsWith("wooden_") || p.startsWith("golden_")) {
			tier = 1;
		}
		if (st.is(ItemTags.SWORDS)) {
			return 100 + tier * 10;
		}
		if (p.equals("mace")) {
			return 160;
		}
		if (p.equals("trident")) {
			return 150;
		}
		if (st.is(ItemTags.AXES)) {
			return 60 + tier * 10;
		}
		if (st.is(ItemTags.PICKAXES)) {
			return 30 + tier * 5;
		}
		if (st.is(ItemTags.SHOVELS)) {
			return 20 + tier * 5;
		}
		return 0;
	}

	/** 手に持たせる。持ち物の実物を持つので、耐久の減りも持ち物に反映される。EMPTY なら素手。 */
	static void equip(FakeMember o, ItemStack s) {
		ItemStack want = s == null ? ItemStack.EMPTY : s;
		ItemStack now = o.getMainHandItem();
		if (now == want) {
			return;
		}
		// UNVERIFIED: setItemSlot が渡した ItemStack の実物をそのまま保持する前提（コピーされても damageTool が手の方にも同期する）。
		// 攻撃力は武器の属性修飾子（装備の変化検知で自動適用）で反映される前提。
		o.setItemSlot(EquipmentSlot.MAINHAND, want);
		o.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
	}

	/** 耐久を n 減らす。0 になったら壊して持ち物から消し、壊れる音を出す。 */
	static void damageTool(FakeMember o, ItemStack s, int n) {
		if (s == null || s.isEmpty() || n <= 0 || !s.isDamageableItem()) {
			return;
		}
		int d = s.getDamageValue() + n;
		ItemStack hand = o.getMainHandItem();
		boolean handIsOther = hand != s && !hand.isEmpty() && hand.is(s.getItem());
		if (d < s.getMaxDamage()) {
			s.setDamageValue(d);
			if (handIsOther) {
				hand.setDamageValue(d);
			}
			return;
		}
		if (o.level() instanceof ServerLevel sl) {
			// ITEM_BREAK は Holder.Reference なので value() で SoundEvent にする
			sl.playSound(null, o.getX(), o.getY(), o.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.8f,
					0.8f + o.getRandom().nextFloat() * 0.4f);
		}
		SimpleContainer c = o.getInventory();
		for (int k = 0; k < c.getContainerSize(); k++) {
			if (c.getItem(k) == s) {
				c.setItem(k, ItemStack.EMPTY);
				break;
			}
		}
		s.setCount(0);
		if (hand == s || handIsOther) {
			o.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		o.postEvent(Names.item(s.getItem()) + "が壊れた");
	}
}
