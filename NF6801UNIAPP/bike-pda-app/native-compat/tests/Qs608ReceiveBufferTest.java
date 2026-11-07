import com.bike.pda.hardware.Qs608ReceiveBuffer;
import java.nio.charset.Charset;
import java.util.*;

public final class Qs608ReceiveBufferTest {
    private static int cases;
    private static class Probe implements Qs608ReceiveBuffer.Listener {
        final List<String> codes = new ArrayList<>();
        final List<Integer> states = new ArrayList<>();
        int rejected;
        final Qs608ReceiveBuffer parser = new Qs608ReceiveBuffer(this);
        public void barcode(String value) { codes.add(value); }
        public void status(int value) { states.add(value); }
        public void rejected(String reason) { rejected++; }
    }
    private static byte[] bytes(String value) { return value.getBytes(Charset.forName("UTF-8")); }
    private static byte[] join(byte[]... values) {
        int size = 0; for (byte[] value : values) size += value.length;
        byte[] result = new byte[size]; int position = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, result, position, value.length); position += value.length; }
        return result;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void verify(byte[] input, List<String> expected, int... cuts) {
        Probe probe = new Probe(); int start = 0;
        for (int cut : cuts) { probe.parser.accept(Arrays.copyOfRange(input, start, cut)); start = cut; }
        probe.parser.accept(Arrays.copyOfRange(input, start, input.length)); probe.parser.idle();
        check(probe.codes.equals(expected), "Payload changed/lost/duplicated for cuts " + Arrays.toString(cuts) + ": " + probe.codes);
        cases++;
    }
    public static void main(String[] args) {
        byte[] ready = {0x14, 0, 0, 0x0F}, missing = {0x14, 0, 0x0C, 0x0F};
        byte[] printReport = {(byte) 0xD1, 0x7A, (byte) 0xF8, 0x4D};
        List<byte[]> inputs = Arrays.asList(
            bytes("##55UP\r\nBIKE-20261102-001\r\n"),
            bytes("BIKE-20261102-001\r\n##55UP\r\n"),
            bytes("\u001b##55UP##56UPBIKE-20261102-001\r\n"),
            join(missing, bytes("##55UP\r\n"), ready, printReport, bytes("BIKE-20261102-001\r\n")),
            bytes("start\r\nBE\r\nP\r\nMM\r\nno paper\r\nBIKE-20261102-001\r\n")
        );
        for (byte[] input : inputs) {
            List<String> expected = Arrays.asList("BIKE-20261102-001");
            for (int first = 0; first <= input.length; first++)
                for (int second = first; second <= input.length; second++) verify(input, expected, first, second);
            int[] everyByte = new int[input.length]; for (int i = 0; i < input.length; i++) everyByte[i] = i;
            verify(input, expected, everyByte);
        }
        for (String charset : Arrays.asList("UTF-8", "GBK")) {
            byte[] input = "车辆-001\r\n".getBytes(Charset.forName(charset));
            for (int cut = 0; cut <= input.length; cut++) verify(input, Arrays.asList("车辆-001"), cut);
        }
        verify(bytes("BIKE-1\r\nBIKE-2\rBIKE-3\nBIKE-4"), Arrays.asList("BIKE-1", "BIKE-2", "BIKE-3", "BIKE-4"));
        verify(bytes("BIKE-1\r\nBIKE-1\r\n"), Arrays.asList("BIKE-1", "BIKE-1"));
        verify(bytes("BIKE-11-START-001\r\n"), Arrays.asList("BIKE-11-START-001"));
        verify(bytes("BIKE-z"), Arrays.asList("BIKE-z"));
        verify(bytes("BIKE-#"), Arrays.asList("BIKE-#"));
        verify(bytes("BIKE-1##55UPBIKE-2\r\n"), Collections.emptyList());
        for (String value : Arrays.asList("##5", "##55U", "BIKE\u0000BROKEN\r\n", "\u001b##55"))
            verify(bytes(value), Collections.emptyList());
        verify(new byte[]{(byte) 0xFF, 13, 10}, Collections.emptyList());
        byte[] oversized = new byte[9000]; Arrays.fill(oversized, (byte) 'A');
        verify(join(oversized, bytes("\r\nBIKE-RECOVERED\r\n")), Arrays.asList("BIKE-RECOVERED"));
        Probe statuses = new Probe(); statuses.parser.accept(join(missing, ready, printReport)); statuses.parser.idle();
        check(statuses.states.equals(Arrays.asList(2, 1, 3)) && statuses.codes.isEmpty(), "Status classification mismatch");
        Probe canceled = new Probe(); canceled.parser.accept(bytes("OLD-PARTIAL")); canceled.parser.reset();
        canceled.parser.accept(bytes("NEW-CODE\r\n")); canceled.parser.idle();
        check(canceled.codes.equals(Arrays.asList("NEW-CODE")), "Previous request leaked");
        Random random = new Random(608);
        for (int round = 0; round < 1000; round++) {
            Probe probe = new Probe(); List<String> expected = new ArrayList<>();
            byte[] input = new byte[0];
            for (int index = 0; index < 10; index++) {
                String code = "BIKE-" + round + "-" + index; expected.add(code);
                input = join(input, bytes("##55UP\r\n"), ready, bytes(code + "\r\n"), printReport);
            }
            for (int position = 0; position < input.length;) {
                int end = Math.min(input.length, position + 1 + random.nextInt(31));
                probe.parser.accept(Arrays.copyOfRange(input, position, end)); position = end;
            }
            probe.parser.idle(); check(probe.codes.equals(expected), "Random split lost barcode"); cases++;
        }
        System.out.println("PASS: " + cases + " receive fragmentation/coalescing/encoding/overflow cases; 10000 continuous codes");
    }
}
