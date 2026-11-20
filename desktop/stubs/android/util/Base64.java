package android.util;
public class Base64 {
  public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, URL_SAFE = 8;
  public static String encodeToString(byte[] b, int flags) {
    java.util.Base64.Encoder e = (flags & URL_SAFE) != 0 ? java.util.Base64.getUrlEncoder() : java.util.Base64.getEncoder();
    if ((flags & NO_PADDING) != 0) e = e.withoutPadding();
    return e.encodeToString(b);
  }
  public static byte[] decode(String s, int flags) {
    return ((flags & URL_SAFE) != 0 ? java.util.Base64.getUrlDecoder() : java.util.Base64.getMimeDecoder()).decode(s);
  }
}
