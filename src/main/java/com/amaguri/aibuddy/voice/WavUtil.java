package com.amaguri.aibuddy.voice;

import java.io.ByteArrayOutputStream;

/** 48kHz mono PCM を 16kHz にして WAV(16bit mono) にする。 */
final class WavUtil {
	private WavUtil() {
	}

	/** 3サンプルの平均で 48k→16k に間引く（簡易ローパス）。 */
	static short[] downsample48to16(short[] in, int len) {
		int n = len / 3;
		short[] out = new short[n];
		for (int i = 0; i < n; i++) {
			int s = in[i * 3] + in[i * 3 + 1] + in[i * 3 + 2];
			out[i] = (short) (s / 3);
		}
		return out;
	}

	static byte[] toWav(short[] pcm, int sampleRate) {
		int dataLen = pcm.length * 2;
		ByteArrayOutputStream o = new ByteArrayOutputStream(44 + dataLen);
		o.writeBytes(new byte[] { 'R', 'I', 'F', 'F' });
		le32(o, 36 + dataLen);
		o.writeBytes(new byte[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' });
		le32(o, 16);
		le16(o, 1); // PCM
		le16(o, 1); // mono
		le32(o, sampleRate);
		le32(o, sampleRate * 2);
		le16(o, 2);
		le16(o, 16);
		o.writeBytes(new byte[] { 'd', 'a', 't', 'a' });
		le32(o, dataLen);
		for (short s : pcm) le16(o, s);
		return o.toByteArray();
	}

	private static void le16(ByteArrayOutputStream o, int v) {
		o.write(v & 0xff);
		o.write((v >> 8) & 0xff);
	}

	private static void le32(ByteArrayOutputStream o, int v) {
		le16(o, v & 0xffff);
		le16(o, (v >>> 16) & 0xffff);
	}
}
