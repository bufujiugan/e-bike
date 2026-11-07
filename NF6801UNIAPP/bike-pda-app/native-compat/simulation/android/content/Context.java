package android.content;
import java.util.*;
public class Context {
    public final List<Intent> broadcasts=new ArrayList<>();
    public final Map<String,BroadcastReceiver> receivers=new HashMap<>();
    public Context getApplicationContext() { return this; }
    public String getPackageName() { return "com.bike.pda"; }
    public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) { receivers.put(filter.action,receiver); return null; }
    public void sendBroadcast(Intent intent) { broadcasts.add(intent); }
}
