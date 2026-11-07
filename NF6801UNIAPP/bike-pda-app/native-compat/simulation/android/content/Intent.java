package android.content;
import java.util.HashMap;
public class Intent {
    public final String action; public String targetPackage;
    private final HashMap<String,Object> extras=new HashMap<>();
    public Intent(String action) { this.action=action; }
    public Intent setPackage(String name) { targetPackage=name; return this; }
    public Intent putExtra(String key,String value) { extras.put(key,value); return this; }
    public String getStringExtra(String key) { return (String) extras.get(key); }
}
