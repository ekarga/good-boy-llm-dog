package ai.orca.llmdog.client.voice;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.config.LlmDogConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Mic capture with two modes:
 *  - Push-to-talk: {@link #start()} / {@link #stopAndGetPcm()} bracket a recording.
 *  - Always-on: {@link #startContinuous(Consumer)} keeps the line open and runs an
 *    energy-based voice-activity detector, handing each detected utterance (raw
 *    16-bit LE mono PCM) to the callback.
 * Reads 16kHz mono PCM.
 */
public class MicCapture {
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread captureThread;
    private ByteArrayOutputStream buffer;
    private TargetDataLine line;
    private final int sampleRate;
    private final LlmDogConfig config;

    public MicCapture(LlmDogConfig config) {
        this.config = config;
        this.sampleRate = config.micSampleRateHz;
    }

    public boolean isRecording() {
        return recording.get();
    }

    public synchronized boolean start() {
        if (recording.get()) return false;
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                LlmDogMod.LOGGER.warn("[Voice] microphone line not supported at {} Hz mono 16-bit", sampleRate);
                return false;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            buffer = new ByteArrayOutputStream(64 * 1024);
            recording.set(true);

            captureThread = new Thread(() -> {
                byte[] chunk = new byte[4096];
                while (recording.get()) {
                    int n = line.read(chunk, 0, chunk.length);
                    if (n > 0) buffer.write(chunk, 0, n);
                }
            }, "llm-dog-mic");
            captureThread.setDaemon(true);
            captureThread.start();
            LlmDogMod.LOGGER.info("[Voice] mic capture started");
            return true;
        } catch (LineUnavailableException e) {
            LlmDogMod.LOGGER.warn("[Voice] mic line unavailable: {}", e.getMessage());
            return false;
        }
    }

    public synchronized byte[] stopAndGetWav() {
        byte[] pcm = stopAndGetPcm();
        return pcm == null ? null : wrapWav(pcm, sampleRate);
    }

    /** Stop recording and return raw 16-bit LE mono PCM. */
    public synchronized byte[] stopAndGetPcm() {
        if (!recording.get()) return null;
        recording.set(false);
        try {
            if (captureThread != null) captureThread.join(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (line != null) {
            try { line.stop(); line.drain(); line.close(); } catch (Exception ignored) {}
        }
        byte[] pcm = buffer != null ? buffer.toByteArray() : new byte[0];
        LlmDogMod.LOGGER.info("[Voice] mic stopped, {} pcm bytes ({} ms)", pcm.length,
            pcm.length * 1000L / (sampleRate * 2));
        if (pcm.length < sampleRate * 2 / 4) {
            return null;
        }
        return pcm;
    }

    /**
     * Always-on capture. Opens the line once and runs an energy-based VAD on a
     * daemon thread; each detected utterance is delivered to {@code onUtterance}
     * as raw 16-bit LE mono PCM. Call {@link #stopContinuous()} to tear down.
     */
    public synchronized boolean startContinuous(Consumer<byte[]> onUtterance) {
        return startContinuous(onUtterance, null);
    }

    /**
     * Always-on capture with streaming: while an utterance is still in
     * progress, {@code onPartial} (nullable) receives growing snapshots of the
     * utterance-so-far every ~{@code streamPartialStrideMs} of new audio, so
     * the command pipeline can react before the speaker stops talking. The
     * callback runs on the VAD thread — it MUST hand off immediately and never
     * block, or the mic line will overrun.
     */
    public synchronized boolean startContinuous(Consumer<byte[]> onUtterance, Consumer<byte[]> onPartial) {
        if (recording.get()) return false;
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                LlmDogMod.LOGGER.warn("[Voice] microphone line not supported at {} Hz mono 16-bit", sampleRate);
                return false;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format);
            line.start();
            recording.set(true);
            captureThread = new Thread(() -> vadLoop(onUtterance, onPartial), "llm-dog-mic-vad");
            captureThread.setDaemon(true);
            captureThread.start();
            LlmDogMod.LOGGER.info("[Voice] always-on VAD capture started (startRms={}, silence={}ms)",
                config.vadStartRms, config.vadSilenceMs);
            return true;
        } catch (LineUnavailableException e) {
            LlmDogMod.LOGGER.warn("[Voice] mic line unavailable: {}", e.getMessage());
            return false;
        }
    }

    public synchronized void stopContinuous() {
        if (!recording.get()) return;
        recording.set(false);
        try { if (captureThread != null) captureThread.join(800); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (line != null) {
            try { line.stop(); line.drain(); line.close(); } catch (Exception ignored) {}
        }
    }

    private void vadLoop(Consumer<byte[]> onUtterance, Consumer<byte[]> onPartial) {
        final int bytesPerSec = sampleRate * 2; // 16-bit mono
        final long silenceLimit = (long) config.vadSilenceMs * bytesPerSec / 1000;
        final long maxUtterBytes = (long) config.vadMaxUtteranceMs * bytesPerSec / 1000;
        final long minUtterBytes = (long) config.vadMinUtteranceMs * bytesPerSec / 1000;
        final int prerollBytes = Math.max(0, config.vadPrerollMs * bytesPerSec / 1000);
        final boolean streaming = onPartial != null && config.streamPartials;
        final long partialStrideBytes = Math.max(1, (long) config.streamPartialStrideMs * bytesPerSec / 1000);
        final long minPartialBytes = (long) config.streamMinPartialMs * bytesPerSec / 1000;

        byte[] chunk = new byte[2048]; // ~64ms at 16kHz/16-bit
        ArrayDeque<byte[]> preroll = new ArrayDeque<>();
        int prerollSize = 0;
        ByteArrayOutputStream utter = null;
        boolean speaking = false;
        long silenceBytes = 0;
        long lastPartialMark = 0;
        // Level meter: peak RMS per window, logged so a dead mic (permission
        // denied -> all zeros) is visible instead of silently hearing nothing.
        double peakRms = 0;
        long lastLevelLog = System.currentTimeMillis();

        while (recording.get()) {
            int n = line.read(chunk, 0, chunk.length);
            if (n <= 0) continue;
            double rms = rms(chunk, n);
            if (rms > peakRms) peakRms = rms;
            long now = System.currentTimeMillis();
            if (now - lastLevelLog >= 15000) {
                LlmDogMod.LOGGER.info("[Voice] mic level: peak RMS {} over last 15s (trigger threshold {}{})",
                    (int) peakRms, config.vadStartRms, peakRms < 10 ? " — mic looks DEAD/muted" : "");
                peakRms = 0;
                lastLevelLog = now;
            }

            if (!speaking) {
                // keep a short rolling pre-roll so the first syllable isn't clipped
                preroll.addLast(Arrays.copyOf(chunk, n));
                prerollSize += n;
                while (prerollSize > prerollBytes && !preroll.isEmpty()) {
                    prerollSize -= preroll.removeFirst().length;
                }
                if (rms >= config.vadStartRms) {
                    speaking = true;
                    silenceBytes = 0;
                    lastPartialMark = 0;
                    utter = new ByteArrayOutputStream(64 * 1024);
                    for (byte[] p : preroll) utter.write(p, 0, p.length);
                    preroll.clear();
                    prerollSize = 0;
                    utter.write(chunk, 0, n);
                }
            } else {
                utter.write(chunk, 0, n);
                if (rms < config.vadKeepRms) silenceBytes += n;
                else silenceBytes = 0;

                // Streaming: hand growing snapshots to the partial pipeline so
                // commands can fire mid-sentence. Handler must not block.
                if (streaming && utter.size() >= minPartialBytes
                        && utter.size() - lastPartialMark >= partialStrideBytes) {
                    lastPartialMark = utter.size();
                    try { onPartial.accept(utter.toByteArray()); }
                    catch (Exception e) { LlmDogMod.LOGGER.warn("[Voice] partial handler error: {}", e.getMessage()); }
                }

                boolean ended = silenceBytes >= silenceLimit;
                boolean tooLong = utter.size() >= maxUtterBytes;
                if (ended || tooLong) {
                    byte[] pcm = utter.toByteArray();
                    speaking = false;
                    utter = null;
                    silenceBytes = 0;
                    if (pcm.length >= minUtterBytes) {
                        LlmDogMod.LOGGER.info("[Voice] utterance captured ({} ms)", pcm.length * 1000L / bytesPerSec);
                        try { onUtterance.accept(pcm); }
                        catch (Exception e) { LlmDogMod.LOGGER.warn("[Voice] utterance handler error: {}", e.getMessage()); }
                    }
                }
            }
        }
    }

    /** RMS amplitude of a 16-bit LE mono PCM chunk (first {@code n} bytes). */
    private static double rms(byte[] b, int n) {
        int samples = n / 2;
        if (samples == 0) return 0;
        long sum = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / samples);
    }

    /** Wrap raw 16-bit PCM mono into a minimal RIFF/WAV. */
    private static byte[] wrapWav(byte[] pcm, int sampleRate) {
        int numChannels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcm.length;
        int chunkSize = 36 + dataSize;

        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        out.write('R'); out.write('I'); out.write('F'); out.write('F');
        writeLE32(out, chunkSize);
        out.write('W'); out.write('A'); out.write('V'); out.write('E');
        out.write('f'); out.write('m'); out.write('t'); out.write(' ');
        writeLE32(out, 16);              // subchunk1 size
        writeLE16(out, 1);                // PCM
        writeLE16(out, numChannels);
        writeLE32(out, sampleRate);
        writeLE32(out, byteRate);
        writeLE16(out, blockAlign);
        writeLE16(out, bitsPerSample);
        out.write('d'); out.write('a'); out.write('t'); out.write('a');
        writeLE32(out, dataSize);
        try { out.write(pcm); } catch (Exception ignored) {}
        return out.toByteArray();
    }

    private static void writeLE32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >>> 8) & 0xff); o.write((v >>> 16) & 0xff); o.write((v >>> 24) & 0xff);
    }
    private static void writeLE16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff); o.write((v >>> 8) & 0xff);
    }
}
