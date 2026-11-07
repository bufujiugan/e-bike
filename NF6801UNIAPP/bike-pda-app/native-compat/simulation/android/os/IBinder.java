package android.os;
public interface IBinder { boolean transact(int code,Parcel request,Parcel reply,int flags); boolean pingBinder(); }
