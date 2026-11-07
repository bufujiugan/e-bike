import com.bike.pda.hardware.Qs608Hardware;
import com.bike.pda.hardware.Qs608Transport;
import com.qs.n601.utils.PrintUtils;
import android.os.Handler;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class Qs608TransportTest {
    public static void main(String[] args) {
        String scenario = args[0];
        final int[] completed = {0}, writes = {0};
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        PrintUtils.mICommService = () -> scenario.equals("busy") ? 1 : SystemClock.now < 30 ? 1 : 0;
        PrintUtils.mOutputStream = new OutputStream() {
            public void write(int value) { throw new AssertionError("Unexpected single-byte write"); }
            public void write(byte[] bytes, int offset, int count) throws IOException {
                check(count > 0 && count <= 1000, "Invalid packet size");
                writes[0]++;
                if (scenario.equals("write-error") && writes[0] == 2) {
                    received.write(bytes, offset, 7);
                    throw new IOException("Injected partial write");
                }
                received.write(bytes, offset, count);
            }
        };
        byte[] first = new byte[2501];
        for (int i = 0; i < first.length; i++) first[i] = (byte) i;
        byte[] second = {11, 22, 33};
        Qs608Transport.enqueue(first, () -> completed[0]++);
        Qs608Transport.enqueue(second, () -> completed[0]++);
        Handler.drain();
        if (scenario.equals("success")) {
            byte[] expected = Arrays.copyOf(first, 2504);
            System.arraycopy(second, 0, expected, 2501, 3);
            check(Arrays.equals(expected, received.toByteArray()), "Packets reordered, dropped or duplicated");
            check(completed[0] == 2 && writes[0] == 4 && Qs608Hardware.failure == null, "Unexpected completion");
        } else {
            check(completed[0] == 0 && Qs608Hardware.failure != null, "Failure reported as success");
            if (scenario.equals("busy")) check(writes[0] == 0 && SystemClock.now == 10000, "Busy deadline mismatch");
            else check(writes[0] == 2 && received.size() == 1007, "Partial write was replayed");
            try { Qs608Transport.enqueue(second, () -> completed[0]++); throw new AssertionError("Failed transport reused"); }
            catch (IllegalStateException expected) { check(completed[0] == 0, "Retry callback executed"); }
        }
        System.out.println("PASS: serial queue " + scenario);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
