package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;

/** 1つの行動。SkillRunner が tick を回す。 */
abstract class Skill {
	protected final FakeMember o;
	protected final SkillRunner runner;
	protected final Digger digger;
	protected final Nav nav;

	boolean finished;
	/** 終了時に脳に返す短い結果（status に残る） */
	String result;
	/** 終了時に postEvent する文（null なら報告しない） */
	String event;

	Skill(FakeMember o, SkillRunner runner) {
		this.o = o;
		this.runner = runner;
		this.digger = new Digger(o);
		this.nav = new Nav(o, digger);
	}

	/** 開始直後に1回呼ばれる。できないなら理由（「できない: ...」）を返す。 */
	String begin() {
		return null;
	}

	abstract void tick();

	abstract String status();

	void onStop() {
		nav.stop();
		digger.cancel();
		o.setShiftKeyDown(false);
	}

	protected void finish(String result, String event) {
		this.finished = true;
		this.result = result;
		this.event = event;
		nav.stop();
		digger.cancel();
	}
}
