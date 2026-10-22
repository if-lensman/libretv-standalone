package family.libretv.tv;
import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
final class PairingQr {
    private PairingQr() { }
    static Bitmap create(String url) throws Exception {
        return create(url,360);
    }
    static Bitmap create(String url,int size) throws Exception {
        EnumMap<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET,"UTF-8");
        hints.put(EncodeHintType.MARGIN, 4); hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size*size];
        for (int y=0; y<size; y++) for (int x=0; x<size; x++) pixels[y*size+x] = matrix.get(x,y) ? 0xff000000 : 0xffffffff;
        return Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888);
    }
}
