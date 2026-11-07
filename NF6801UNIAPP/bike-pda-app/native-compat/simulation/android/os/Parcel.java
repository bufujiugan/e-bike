package android.os;
public class Parcel {
    public String token; public int value;
    public static Parcel obtain() { return new Parcel(); }
    public void writeInterfaceToken(String token) { this.token=token; }
    public void writeInt(int value) { this.value=value; }
    public void readException() {}
    public int readInt() { return value; }
    public void recycle() {}
}
