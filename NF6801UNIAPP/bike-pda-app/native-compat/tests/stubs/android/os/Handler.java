package android.os;
import java.util.PriorityQueue;
public final class Handler {
    private static long sequence;
    private static final PriorityQueue<Pending> QUEUE = new PriorityQueue<>();
    private static final class Pending implements Comparable<Pending> {
        final Runnable callback; final long time, order;
        Pending(Runnable callback, long time) { this.callback = callback; this.time = time; this.order = sequence++; }
        public int compareTo(Pending other) { int byTime = Long.compare(time, other.time); return byTime != 0 ? byTime : Long.compare(order, other.order); }
    }
    public Handler(Looper looper) {}
    public boolean post(Runnable callback) { return postDelayed(callback, 0); }
    public void removeCallbacks(Runnable callback) { QUEUE.removeIf(item -> item.callback == callback); }
    public boolean postDelayed(Runnable callback, long delay) { QUEUE.add(new Pending(callback, SystemClock.now + delay)); return true; }
    public static void drain() {
        int remaining = 10000;
        while (!QUEUE.isEmpty()) {
            if (--remaining == 0) throw new AssertionError("Unbounded queue");
            Pending pending = QUEUE.remove(); SystemClock.now = pending.time; pending.callback.run();
        }
    }
}
