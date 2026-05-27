package ai.orca.llmdog.client.voice;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.config.LlmDogConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Push-to-talk mic capture. Reads 16kHz mono PCM into a buffer while
 * recording, returns a WAV-wrapped byte array when stopped.
 */
public class MicCapture {
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread captureThread;
    private ByteArrayOutputStream buffer;
    private TargetDataLine line;
    private final int sampleRate;

    public MicCapture(LlmDogConfig config) {
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
