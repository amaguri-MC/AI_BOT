package com.amaguri.aibuddy.client;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * モデル空間（identity の PoseStack で描かせたもの）の頂点を受け取り、4頂点ごとに細分して
 * {@link MeltDeform} で変形し、本来のポーズ(pose)で変換して out に流す。
 * ModelPart は1面=4頂点(QUADS)で出力する。細分しないと大きな面が4隅の線形補間にしかならず溶けて見えない。
 * 頂点は addVertex の11引数版でも、addVertex(x,y,z).setXxx() の連鎖でも受けられる。
 */
final class MeltVertexConsumer implements VertexConsumer {
	/** 細分の目安（ブロック）。 */
	private static final float CELL = 0.17f;
	private static final int MAX_DIV = 6;

	private final VertexConsumer out;
	private final PoseStack.Pose pose;
	private final float melt;
	private final float time;

	private final float[][] pos = new float[4][3];
	private final int[] color = {-1, -1, -1, -1};
	private final float[] us = new float[4];
	private final float[] vs = new float[4];
	private final int[] overlay = new int[4];
	private final int[] light = new int[4];
	private final float[][] nrm = new float[4][3];
	private int cur;
	private boolean open;

	private final float[] tmp = new float[3];
	private final Vector3f tp = new Vector3f();
	private final Vector3f tn = new Vector3f();
	private final float[][] grid = new float[(MAX_DIV + 1) * (MAX_DIV + 1)][3];

	MeltVertexConsumer(VertexConsumer out, PoseStack.Pose pose, float melt, float time) {
		this.out = out;
		this.pose = pose;
		this.melt = melt;
		this.time = time;
	}

	/** 描画が終わったら呼ぶ（最後の頂点を確定させる）。 */
	void finish() {
		closeVertex();
	}

	private void closeVertex() {
		if (!open) {
			return;
		}
		open = false;
		cur++;
		if (cur == 4) {
			cur = 0;
			emitQuad();
		}
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		closeVertex();
		open = true;
		pos[cur][0] = x;
		pos[cur][1] = y;
		pos[cur][2] = z;
		return this;
	}

	@Override
	public void addVertex(float x, float y, float z, int argb, float u, float v, int ov, int lt, float nx, float ny, float nz) {
		closeVertex();
		open = true;
		pos[cur][0] = x;
		pos[cur][1] = y;
		pos[cur][2] = z;
		color[cur] = argb;
		us[cur] = u;
		vs[cur] = v;
		overlay[cur] = ov;
		light[cur] = lt;
		nrm[cur][0] = nx;
		nrm[cur][1] = ny;
		nrm[cur][2] = nz;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		color[cur] = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
		return this;
	}

	@Override
	public VertexConsumer setColor(int argb) {
		color[cur] = argb;
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		us[cur] = u;
		vs[cur] = v;
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		overlay[cur] = (u & 0xFFFF) | (v << 16);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		light[cur] = (u & 0xFFFF) | (v << 16);
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		nrm[cur][0] = x;
		nrm[cur][1] = y;
		nrm[cur][2] = z;
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float w) {
		return this;
	}

	private static float dist(float[] a, float[] b) {
		float dx = a[0] - b[0];
		float dy = a[1] - b[1];
		float dz = a[2] - b[2];
		return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static int div(float len) {
		return Math.max(1, Math.min(MAX_DIV, (int) Math.ceil(len / CELL)));
	}

	private void emitQuad() {
		float[] a = pos[0];
		float[] b = pos[1];
		float[] d = pos[3];
		int nu = div(dist(a, b));
		int nv = div(dist(a, d));
		// 変形後の位置を格子ごとに1回だけ計算
		for (int j = 0; j <= nv; j++) {
			float t = (float) j / nv;
			for (int i = 0; i <= nu; i++) {
				float s = (float) i / nu;
				float[] g = grid[j * (nu + 1) + i];
				for (int k = 0; k < 3; k++) {
					g[k] = pos[0][k] * (1 - s) * (1 - t) + pos[1][k] * s * (1 - t) + pos[2][k] * s * t + pos[3][k] * (1 - s) * t;
				}
				MeltDeform.deform(g, melt, time);
			}
		}
		for (int j = 0; j < nv; j++) {
			for (int i = 0; i < nu; i++) {
				put(i, j, nu, nv);
				put(i + 1, j, nu, nv);
				put(i + 1, j + 1, nu, nv);
				put(i, j + 1, nu, nv);
			}
		}
	}

	private void put(int i, int j, int nu, int nv) {
		float s = (float) i / nu;
		float t = (float) j / nv;
		float[] g = grid[j * (nu + 1) + i];
		pose.pose().transformPosition(g[0], g[1], g[2], tp);
		pose.transformNormal(nrm[0][0], nrm[0][1], nrm[0][2], tn);
		float u = us[0] * (1 - s) * (1 - t) + us[1] * s * (1 - t) + us[2] * s * t + us[3] * (1 - s) * t;
		float v = vs[0] * (1 - s) * (1 - t) + vs[1] * s * (1 - t) + vs[2] * s * t + vs[3] * (1 - s) * t;
		out.addVertex(tp.x, tp.y, tp.z, color[0], u, v, overlay[0], light[0], tn.x, tn.y, tn.z);
	}
}
