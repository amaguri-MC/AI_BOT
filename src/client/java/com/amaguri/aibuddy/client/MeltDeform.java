package com.amaguri.aibuddy.client;

/**
 * 溶けの頂点変形（純粋な数式。Minecraft クラスに依存しない）。
 * agent/tasks/b-client/melt_preview.py が同じ式を Python で再現している。式を変えたら両方直すこと。
 *
 * 入力はプレイヤーモデルのモデル空間（ブロック単位、y は下向き正。足元 y=1.5、頭頂 y=-0.5）。
 * m: 溶け 0..1、t: 時間(tick)。p = {x, y, z} を直接書き換える。m<=0 では何もしない。
 */
public final class MeltDeform {
	public static final float FEET_Y = 1.5f;
	public static final float MODEL_HEIGHT = 2.0f;

	private MeltDeform() {
	}

	public static float smooth(float a, float b, float v) {
		float x = Math.min(1f, Math.max(0f, (v - a) / (b - a)));
		return x * x * (3f - 2f * x);
	}

	/** 背の縮み率。後半に寄せる: 1-0.8*m^2.2。終盤(0.85〜)で平らに潰れ、m=1 で 0.05（厚み 約0.1 のプール）。 */
	public static float heightScale(float m) {
		float pm = smooth(0.85f, 1f, m);
		return (1f - 0.8f * (float) Math.pow(m, 2.2)) * (1f - 0.75f * pm);
	}

	public static void deform(float[] p, float m, float t) {
		if (m <= 0f) {
			return;
		}
		float x0 = p[0];
		float y0 = p[1];
		float z0 = p[2];
		float u = FEET_Y - y0;
		float h = Math.min(1f, Math.max(0f, u / MODEL_HEIGHT));
		float pm = smooth(0.85f, 1f, m);

		// 横: 下ほど広がる。溶けが進むと全体が広がり、上限(水たまりの大きさ)で丸める
		float spread = 1f + 1.2f * m * (1f - h) + 1.4f * pm;
		float r = (float) Math.sqrt(x0 * x0 + z0 * z0);
		float rr = r * spread;
		float w = smooth(0.3f, 0.9f, m);
		float cap = 1.1f;
		float capped = (float) (rr / Math.pow(1.0 + Math.pow(rr / cap, 4.0), 0.25));
		rr = rr + (capped - rr) * w;
		if (r > 1.0e-4f) {
			float ang = (float) Math.atan2(z0, x0);
			rr *= 1f + pm * 0.08f * (float) Math.sin(5f * ang + t * 0.08f);
		}
		float k = r > 1.0e-4f ? rr / r : 1f;
		float x = x0 * k;
		float z = z0 * k;

		// 横揺れ（うねり）
		float wob = m * h * 0.04f * (1f - pm);
		x += wob * (float) Math.sin(t * 0.15f + y0 * 6f + z * 5f);
		z += wob * (float) Math.sin(t * 0.13f + y0 * 5f + x * 6f);

		// 縦: 後半の縮み + 上ほど下へ垂れる。顔の側面(頭の左右端)が先に垂れる
		float u2 = u * heightScale(m);
		float m16 = (float) Math.pow(m, 1.6);
		float droop = m16 * (0.1f + 0.6f * (float) Math.pow(h, 1.5)) * (1f + 0.25f * (float) Math.sin(t * 0.12f + x * 9f + z * 7f + h * 5f));
		if (h > 0.7f) {
			float side = smooth(0.08f, 0.25f, Math.abs(x0));
			droop += side * smooth(0.1f, 0.6f, m) * 0.3f * m;
		}
		// 中盤: 頭が首にめり込み、肩と腕が体に垂れ落ちる
		if (y0 < 0f) {
			droop += smooth(0.45f, 0.9f, m) * 0.3f;
		}
		if (Math.abs(x0) > 0.26f && y0 < 0.8f) {
			droop += smooth(0.4f, 0.85f, m) * 0.5f * (1f - Math.max(0f, y0) / 0.8f);
		}
		// 顔: 目と口の位置が下へずれて歪む（あご側ほど大きくずれる）
		if (y0 < 0.02f && y0 > -0.6f && z0 < -0.1f) {
			float front = smooth(-0.1f, -0.35f, z0);
			droop += front * smooth(0.05f, 0.6f, m) * (y0 + 0.5f) * 0.5f * (1f + 0.4f * (float) Math.sin(x0 * 14f + t * 0.05f));
		}
		u2 -= droop * (1f - pm);
		// 垂れ: 頭の下端(あご)と、腕・服のすその下端から細い滴が下へ伸びる（ゆっくり伸び縮み）
		float env = smooth(0.05f, 0.3f, m) * (1f - smooth(0.6f, 0.9f, m));
		if (env > 0f) {
			float near = Math.max(drip(y0, 0.0f), drip(y0, 0.75f));
			if (near > 0f) {
				float rnd = 0.5f + 0.5f * (float) Math.sin(x0 * 91f + z0 * 67f + y0 * 13f);
				rnd = rnd * rnd * rnd;
				float len = 0.25f * env * rnd * (0.75f + 0.25f * (float) Math.sin(t * 0.07f + x0 * 40f + z0 * 30f));
				u2 -= near * len;
			}
		}
		u2 += 0.015f * m * (1f - pm) * (float) Math.sin(x0 * 53f + y0 * 41f + z0 * 47f);
		float floor = 0.02f * smooth(0f, 0.1f, m);
		if (u2 < floor) {
			u2 = floor;
		}
		p[0] = x;
		p[1] = FEET_Y - u2;
		p[2] = z;
	}

	/** 下端 yb（モデル空間, y下向き正）の上 0.12 ブロック以内で 0→1。下端より下 0.05 まではそのまま 1。 */
	private static float drip(float y, float yb) {
		if (y > yb + 0.05f) {
			return 0f;
		}
		return smooth(yb - 0.12f, yb, y);
	}
}
