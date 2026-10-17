package me.cortex.voxy.autotest;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

public final class FrameStats {
    private long lastFrame = -1;
    private final LongArrayList deltas = new LongArrayList();
    private boolean recording;
    public int glErrors;

    public void onFrameEnd() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            this.glErrors++;
        }
        long now = System.nanoTime();
        if (this.recording && this.lastFrame != -1) {
            this.deltas.add(now - this.lastFrame);
        }
        this.lastFrame = now;
    }

    public void startRecording() {
        this.deltas.clear();
        this.recording = true;
    }

    public void stopRecording() {
        this.recording = false;
    }

    public int recorded() {
        return this.deltas.size();
    }

    public double meanMs() {
        if (this.deltas.isEmpty()) return 0;
        long sum = 0;
        for (int i = 0; i < this.deltas.size(); i++) sum += this.deltas.getLong(i);
        return sum / 1e6 / this.deltas.size();
    }

    public double p99Ms() {
        return this.percentileMs(0.99);
    }

    public double percentileMs(double p) {
        if (this.deltas.isEmpty()) return 0;
        long[] sorted = this.deltas.toLongArray();
        Arrays.sort(sorted);
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p) - 1))] / 1e6;
    }

    //Frame times in recording order, for spotting periodic stalls
    public double[] framesMs() {
        double[] out = new double[this.deltas.size()];
        for (int i = 0; i < out.length; i++) out[i] = this.deltas.getLong(i) / 1e6;
        return out;
    }
}
