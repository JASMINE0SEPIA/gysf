package dev.comfyfluffy.caustica.rt;

/** Keep shader time small without restarting the physical phase at a clock boundary. */
final class RtWaterWaveClock {
    record Frame(double baseSeconds, float currentTime, float previousTime) {}
    private double lastSeconds;
    private boolean valid;

    Frame at(double seconds) {
        double base = Math.floor(seconds / 8.0) * 8.0;
        float current = (float)(seconds - base);
        double delta = seconds - lastSeconds;
        // History is local to this frame's base, including negative times just after a base change.
        float previous = valid && delta >= 0 && delta <= 0.25 ? (float)(lastSeconds - base) : current;
        lastSeconds = seconds;
        valid = true;
        return new Frame(base, current, previous);
    }

    void resetHistory() { valid = false; }
}
