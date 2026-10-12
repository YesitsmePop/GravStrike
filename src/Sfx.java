import javax.sound.sampled.*;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;

/** Sound effects: built-in chiptune sounds, overridable with /sounds/<name>.wav on the classpath. */
public final class Sfx {
    private Sfx() { }

    private static final double RATE = 22050;
    private static final AudioFormat SYNTH_FORMAT = new AudioFormat((float) RATE, 16, 1, true, false);
    private static final int MAX_VOICES = 16;   // sounds allowed to overlap

    private record Sound(AudioFormat format, byte[] data, int cooldownMs) { }
    private interface Wave { double at(double t, double p); }   // t = seconds, p = progress 0..1

    private static final Map<String, Sound> SOUNDS = new HashMap<>();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final Random RND = new Random(7);
    private static final ExecutorService AUDIO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sfx");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean muted;

    private static final Object HUM_LOCK = new Object();
    private static final float HUM_VOLUME = 0.09f;   // lower = quieter (0.05 is barely there)
    private static Clip humClip;
    private static volatile boolean humWanted;

    private static final int POOL_SIZE = 3;               // clips kept open per short sound
    private static final double SHORT_SECONDS = 0.30;     // sounds shorter than this use the pool
    private static final Map<String, Clip[]> POOL = new HashMap<>();   // touched only on the audio thread
    private static final Map<String, Integer> POOL_NEXT = new HashMap<>();

    static {
        register("tick",         35, blip(1500, 0.015, 0.15));
        register("key",          25, blip(1100, 0.020, 0.22));
        register("type",         25, blip(2200, 0.012, 0.14));
        register("hover",        60, blip(1800, 0.020, 0.12));
        register("click",         0, blip(900,  0.030, 0.30));
        register("scan",         50, blip(1400, 0.040, 0.25));
        register("countdown",     0, blip(880,  0.140, 0.35));
        register("back",          0, sweep(700, 350, 1.0, 0.07, 0.30));
        register("open",          0, sweep(400, 1200, 0.5, 0.12, 0.28));
        register("remove",        0, sweep(900, 150, 1.0, 0.18, 0.30));
        register("drawer_open",  300, sweep(300, 900, 1.2, 0.08, 0.18));
        register("drawer_close", 300, sweep(900, 300, 1.2, 0.08, 0.18));
        register("lock",          0, notes(0.045, 0.30, 800, 1200));
        register("place",         0, notes(0.060, 0.30, 523, 784, 1047, 1568));
        register("accept",        0, notes(0.060, 0.30, 523, 659, 784, 1047));
        register("granted",       0, notes(0.090, 0.30, 523, 659, 784, 1047, 1047, 1319));
        register("deny",          0, synth(0.35, 0.35,
                (t, p) -> square(t * 140) * (square(t * 14) > 0 ? 1.0 : 0.25)));
        register("alarm",         0, siren(3.0, 0.20));
        register("powerdown",     0, powerDown(1.2, 0.35));
        register("lockon",    0, notes(0.06, 0.30, 1400, 0, 1400, 0, 2100));
        register("scope_on",  0, notes(0.045, 0.26, 500, 700, 950, 1400, 1900));
        register("servo",    70, blip(260, 0.025, 0.14));
        register("zoom_in",   0, zoomSweep(1.9, 0.28, true));
        register("zoom_out",  0, zoomSweep(2.3, 0.28, false));
        register("launch",    0, launchSound(0.7, 0.42));
        register("missile",   0, missileSound(1.4, 0.30));
        register("boom",      0, explosionSound(3.4, 0.55));
        register("shatter",   0, shatterSound(2.4, 0.45));
        register("confirm",   0, notes(0.08, 0.30, 784, 784, 1047, 1568, 1568));
    }

    // ------------------------------------------------------------------ public API

    /** Call once at startup so the first sound doesn't lag while the audio device wakes up. */
    public static void init() {
        AUDIO.execute(() -> {
            try { AudioSystem.getClip().close(); } catch (Exception ignored) { }
            for (String n : new String[]{"key", "back", "type", "tick", "click", "hover", "scan"}) {
                Sound s = SOUNDS.get(n);
                if (s == null) continue;
                try {
                    for (int i = 0; i < POOL_SIZE; i++) clipFor(n, s, i);
                } catch (Exception ignored) { }
            }
        });
    }

    public static void setMuted(boolean m) {
        muted = m;
        if (m) fadeOutHum();
        else if (humWanted) startHum();
    }
    public static boolean isMuted()        { return muted; }

    // ------------------------------------------------------------------ ambient hum

    /** Starts the looping CRT hum (fades in). Safe to call more than once. */
    public static void startHum() {
        humWanted = true;
        if (muted) return;
        AUDIO.execute(() -> {
            synchronized (HUM_LOCK) {
                if (humClip != null) return;
                try {
                    Sound s = humSound();
                    Clip c = AudioSystem.getClip();
                    c.open(s.format(), s.data(), 0, s.data().length);
                    setGain(c, -80f);
                    c.loop(Clip.LOOP_CONTINUOUSLY);
                    humClip = c;
                    fade(c, -45f, 0f, 1800, false);
                } catch (Exception ignored) { /* no audio device: stay silent */ }
            }
        });
    }

    /** Fades the hum out and stops it. */
    public static void stopHum() {
        humWanted = false;
        fadeOutHum();
    }

    private static void fadeOutHum() {
        Clip c;
        synchronized (HUM_LOCK) {
            c = humClip;
            humClip = null;
        }
        if (c != null) fade(c, 0f, -60f, 700, true);
    }

    private static Sound humSound() {
        Sound custom = loadWav("hum", 0);            // optional: sounds/hum.wav overrides this
        if (custom != null) return custom;

        double seconds = 2.0;                        // whole cycles of 60/120/180/240 Hz and the 0.5 Hz wobble
        int n = (int) (seconds * RATE);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / RATE, tau = 2 * Math.PI * t;
            double v = 0.60 * Math.sin(tau * 60)
                    + 0.28 * Math.sin(tau * 120 + 0.6)
                    + 0.12 * Math.sin(tau * 180 + 1.3)
                    + 0.06 * Math.sin(tau * 240 + 2.1);
            v *= 0.85 + 0.15 * Math.sin(tau * 0.5);  // slow breathing
            short s = (short) (v * HUM_VOLUME * 32767);
            out[i * 2] = (byte) s;
            out[i * 2 + 1] = (byte) (s >> 8);
        }
        return new Sound(SYNTH_FORMAT, out, 0);
    }

    private static void setGain(Clip c, float db) {
        try {
            FloatControl g = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
            g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
        } catch (Exception ignored) { /* this device has no gain control */ }
    }

    private static void fade(Clip c, float fromDb, float toDb, int ms, boolean closeWhenDone) {
        Thread t = new Thread(() -> {
            int steps = Math.max(1, ms / 30);
            for (int i = 0; i <= steps; i++) {
                setGain(c, fromDb + (toDb - fromDb) * i / steps);
                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
            if (closeWhenDone) {
                c.stop();
                c.close();
            }
        }, "sfx-fade");
        t.setDaemon(true);
        t.start();
    }

    public static void play(String name) {
        if (muted) return;
        Sound s = SOUNDS.get(name);
        if (s == null) return;

        long now = System.currentTimeMillis();
        Long last = LAST.get(name);
        if (last != null && now - last < s.cooldownMs()) return;
        LAST.put(name, now);

        AUDIO.execute(() -> {
            try {
                if (isShort(s)) playPooled(name, s);
                else playOneShot(s);
            } catch (Exception ignored) { /* no audio device: stay silent */ }
        });
    }

    private static boolean isShort(Sound s) {
        AudioFormat f = s.format();
        return s.data().length / (double) (f.getFrameSize() * f.getFrameRate()) < SHORT_SECONDS;
    }

    /** Short sounds: rewind and replay a clip that is already open (no startup delay). */
    private static void playPooled(String name, Sound s) throws Exception {
        int i = POOL_NEXT.merge(name, 1, Integer::sum) % POOL_SIZE;
        Clip c = clipFor(name, s, i);
        c.stop();
        c.setFramePosition(0);
        c.start();
    }

    private static Clip clipFor(String name, Sound s, int i) throws Exception {
        Clip[] ring = POOL.computeIfAbsent(name, k -> new Clip[POOL_SIZE]);
        if (ring[i] == null) {
            AudioFormat f = s.format();
            int pad = (int) (f.getFrameRate() * 0.04) * f.getFrameSize();   // 40 ms of silence
            byte[] data = Arrays.copyOf(s.data(), s.data().length + pad);
            Clip c = AudioSystem.getClip();
            c.open(f, data, 0, data.length);
            ring[i] = c;
        }
        return ring[i];
    }

    /** Longer sounds: open a clip, play it, close it when it finishes. */
    private static void playOneShot(Sound s) throws Exception {
        if (ACTIVE.get() >= MAX_VOICES) return;
        Clip clip = AudioSystem.getClip();
        clip.open(s.format(), s.data(), 0, s.data().length);
        ACTIVE.incrementAndGet();
        clip.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) {
                clip.close();
                ACTIVE.decrementAndGet();
            }
        });
        clip.start();
    }

    // ------------------------------------------------------------------ loading

    private static void register(String name, int cooldownMs, byte[] fallback) {
        Sound loaded = loadWav(name, cooldownMs);
        SOUNDS.put(name, loaded != null ? loaded : new Sound(SYNTH_FORMAT, fallback, cooldownMs));
    }

    private static Sound loadWav(String name, int cooldownMs) {
        URL url = Sfx.class.getResource("/sounds/" + name + ".wav");
        if (url == null) return null;
        try (AudioInputStream raw = AudioSystem.getAudioInputStream(url)) {
            AudioFormat f = raw.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, f.getSampleRate(),
                    16, f.getChannels(), f.getChannels() * 2, f.getSampleRate(), false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, raw)) {
                return new Sound(pcm, converted.readAllBytes(), cooldownMs);
            }
        } catch (Exception e) {
            System.err.println("[Sfx] could not load " + name + ".wav, using built-in sound: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------ synthesis

    private static double square(double phase) {
        return (phase - Math.floor(phase)) < 0.5 ? 1 : -1;
    }

    private static byte[] synth(double seconds, double volume, Wave wave) {
        int n = (int) (seconds * RATE);
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / RATE, p = (double) i / n;
            double env = Math.min(1, i / (RATE * 0.002)) * Math.min(1, (n - i) / (RATE * 0.004)); // no clicks
            double v = wave.at(t, p) * env * volume;
            short s = (short) Math.max(-32768, Math.min(32767, v * 32767));
            out[i * 2] = (byte) s;
            out[i * 2 + 1] = (byte) (s >> 8);
        }
        return out;
    }

    /** One short beep that fades out. */
    private static byte[] blip(double freq, double seconds, double volume) {
        return sweep(freq, freq, 1.5, seconds, volume);
    }

    /** Pitch glide from f0 to f1. decay = how fast it fades (0 = no fade). */
    private static byte[] sweep(double f0, double f1, double decay, double seconds, double volume) {
        double[] ph = {0};
        return synth(seconds, volume, (t, p) -> {
            ph[0] += (f0 + (f1 - f0) * p) / RATE;
            return square(ph[0]) * Math.pow(1 - p, decay);
        });
    }

    /** A run of notes, one after another. A frequency of 0 is a rest. */
    private static byte[] notes(double noteSeconds, double volume, double... freqs) {
        return synth(noteSeconds * freqs.length, volume, (t, p) -> {
            int idx = Math.min(freqs.length - 1, (int) (p * freqs.length));
            double local = p * freqs.length - idx;
            return freqs[idx] == 0 ? 0 : square(t * freqs[idx]) * (1 - local * 0.6);
        });
    }

    private static byte[] siren(double seconds, double volume) {
        double[] ph = {0};
        return synth(seconds, volume, (t, p) -> {
            ph[0] += (750 + 280 * Math.sin(t * Math.PI * 3)) / RATE;
            return square(ph[0]);
        });
    }

    private static byte[] powerDown(double seconds, double volume) {
        double[] ph = {0};
        return synth(seconds, volume, (t, p) -> {
            ph[0] += (30 + 1400 * Math.pow(1 - p, 2.2)) / RATE;
            double noise = RND.nextDouble() * 2 - 1;
            return (0.65 * square(ph[0]) + 0.35 * noise) * Math.pow(1 - p, 0.7);
        });
    }

    /** Rising (or falling) pitch sweep with a bed of noise: the "dive" sound. */
    private static byte[] zoomSweep(double seconds, double volume, boolean up) {
        double[] ph = {0};
        return synth(seconds, volume, (t, p) -> {
            double q = up ? p : 1 - p;
            ph[0] += 90 * Math.pow(24, q) / RATE;                 // 90 Hz up to about 2 kHz
            double env = up ? 0.35 + 0.65 * p : 1 - 0.7 * p;
            return (0.55 * square(ph[0]) + 0.25 * (RND.nextDouble() * 2 - 1)) * env;
        });
    }

    private static byte[] launchSound(double seconds, double volume) {
        double[] ph = {0}, lp = {0};
        return synth(seconds, volume, (t, p) -> {
            lp[0] += 0.35 * ((RND.nextDouble() * 2 - 1) - lp[0]);
            ph[0] += (420 - 340 * p) / RATE;
            return (0.7 * lp[0] + 0.4 * square(ph[0])) * Math.pow(1 - p, 1.3);
        });
    }

    /** Rocket hiss plus a rising whine that swells as the missile closes in (1.4s, matches the flight). */
    private static byte[] missileSound(double seconds, double volume) {
        double[] ph = {0}, lp = {0};
        return synth(seconds, volume, (t, p) -> {
            double white = RND.nextDouble() * 2 - 1;
            lp[0] += (0.08 + 0.40 * p) * (white - lp[0]);          // hiss gets brighter as it approaches
            ph[0] += (180 + 520 * p * p) / RATE;                    // whine climbs from 180 Hz to 700 Hz
            double swell = 0.25 + 0.75 * Math.pow(p, 1.5);
            return (0.9 * lp[0] + 0.25 * square(ph[0])) * swell;
        });
    }

    /** Big impact: sharp crack, deep sub thump, long fading rumble (3.4s). */
    private static byte[] explosionSound(double seconds, double volume) {
        double[] lpBody = {0}, lpRumble = {0}, ph = {0};
        return synth(seconds, volume, (t, p) -> {
            double white = RND.nextDouble() * 2 - 1;

            double crack = white * Math.exp(-t * 28);               // initial snap

            lpBody[0] += 0.25 * (white - lpBody[0]);                // mid-heavy blast body
            double body = lpBody[0] * Math.exp(-t * 2.2);

            lpRumble[0] += 0.03 * (white - lpRumble[0]);            // low rolling rumble
            double rumble = lpRumble[0] * 6 * Math.exp(-t * 1.1) * (0.7 + 0.3 * Math.sin(t * 23));

            ph[0] += (28 + 70 * Math.exp(-t * 6)) / RATE;           // sub thump, drops from about 98 Hz to 28 Hz
            double thump = Math.sin(2 * Math.PI * ph[0]) * Math.exp(-t * 3.2);

            return Math.tanh(0.55 * crack + 0.8 * body + 0.7 * rumble + 0.9 * thump);   // soft clip
        });
    }

    /** Planet breaking apart: heavy crunch, crackling rock debris, crumbling tail (2.4s). */
    private static byte[] shatterSound(double seconds, double volume) {
        double[] lp = {0}, ph = {0}, burst = {0};
        return synth(seconds, volume, (t, p) -> {
            double white = RND.nextDouble() * 2 - 1;

            double crack = white * Math.exp(-t * 20);               // opening fracture

            ph[0] += (220 * Math.exp(-t * 3) + 45) / RATE;          // falling crunch tone
            double crunch = square(ph[0]) * Math.exp(-t * 5);

            if (RND.nextDouble() < 0.0027 * (1 - p) * (1 - p))      // random rock clicks, thinning out over time
                burst[0] = 0.5 + 0.5 * RND.nextDouble();
            double clicks = white * burst[0];
            burst[0] *= 0.989;                                      // each click dies in a few ms

            lp[0] += 0.18 * (white - lp[0]);                        // crumbling gravel bed
            double crumble = lp[0] * Math.pow(1 - p, 1.6) * (0.75 + 0.25 * Math.sin(t * 41));

            return Math.tanh(0.6 * crack + 0.35 * crunch + 0.7 * clicks + 1.2 * crumble);
        });
    }

}