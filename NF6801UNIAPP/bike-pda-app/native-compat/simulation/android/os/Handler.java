package android.os;
import java.util.PriorityQueue;
public class Handler {
    private static long sequence;
    private static final PriorityQueue<Pending> QUEUE = new PriorityQueue<>();
    private static final class Pending implements Comparable<Pending> {
        final Handler owner; final Runnable callback; final long time, order;
        Pending(Handler owner, Runnable callback, long time) { this.owner=owner; this.callback=callback; this.time=time; this.order=sequence++; }
        public int compareTo(Pending other) { int byTime=Long.compare(time,other.time); return byTime!=0 ? byTime : Long.compare(order,other.order); }
    }
    public Handler() {}
    public Handler(Looper looper) {}
    public void handleMessage(Message message) {}
    public boolean sendMessage(Message message) { return post(() -> handleMessage(message)); }
    public boolean post(Runnable callback) { return postDelayed(callback,0); }
    public boolean postDelayed(Runnable callback,long delay) { synchronized(QUEUE) { QUEUE.add(new Pending(this,callback,SystemClock.now+delay)); return true; } }
    public void removeCallbacks(Runnable callback) { synchronized(QUEUE) { QUEUE.removeIf(item -> item.owner==this && item.callback==callback); } }
    public static void advance(long milliseconds) {
        long target=SystemClock.now+milliseconds; int remaining=100000;
        while (true) {
            Pending item;
            synchronized(QUEUE) { if (QUEUE.isEmpty() || QUEUE.peek().time>target) break; item=QUEUE.remove(); }
            if (--remaining==0) throw new AssertionError("Unbounded handler queue");
            SystemClock.now=item.time; item.callback.run();
        }
        SystemClock.now=target;
    }
}
