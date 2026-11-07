package android.graphics;
public class Bitmap {
    private final int width,height; public Bitmap(int width,int height) { this.width=width; this.height=height; }
    public int getWidth() { return width; } public int getHeight() { return height; }
    public int getPixel(int x,int y) { return x==width-1 || y==height-1 ? 0xFF000000 : 0xFFFFFFFF; }
}
