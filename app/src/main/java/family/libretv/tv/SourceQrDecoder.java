package family.libretv.tv;

import android.graphics.Bitmap;
import android.net.Uri;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.EnumMap;

/** Decode QR pixels locally. URLs/configuration are only fetched after explicit import. */
final class SourceQrDecoder {
    private SourceQrDecoder() {}
    static String sharePayload(String json) throws Exception {
        byte[] raw=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(raw.length<=1200)return json;
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        try(java.util.zip.DeflaterOutputStream zip=new java.util.zip.DeflaterOutputStream(out)){zip.write(raw);}
        String value="libretv://sources?z="+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        if(value.length()>1600)throw new IllegalArgumentException("配置较多，请导出文件或分享订阅地址");
        return value;
    }
    static String payload(String raw) throws Exception {
        String value=raw.trim();
        if(value.startsWith("libretv://")) {
            Uri uri=Uri.parse(value);
            if(!"sources".equals(uri.getHost())) throw new IllegalArgumentException("不是来源配置二维码");
            String config=uri.getQueryParameter("config"), url=uri.getQueryParameter("url"), compressed=uri.getQueryParameter("z");
            if(compressed!=null) {
                byte[] bytes=android.util.Base64.decode(compressed,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
                value=LocalRepository.read(new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(bytes)),100000);
            } else value=config!=null?config:url;
            if(value==null) throw new IllegalArgumentException("来源二维码缺少配置");
            value=value.trim();
        }
        if(value.length()>100000) throw new IllegalArgumentException("二维码内容过长，请改用文件导入");
        if(value.startsWith("http://") || value.startsWith("https://")) LocalRepository.validate(value);
        else if(value.startsWith("{") || value.startsWith("[")) LocalRepository.parseSources(value);
        else throw new IllegalArgumentException("二维码应包含订阅地址或来源 JSON");
        return value;
    }
    private static String decode(LuminanceSource pixels) throws Exception {
        EnumMap<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.CHARACTER_SET,"UTF-8"); hints.put(DecodeHintType.TRY_HARDER,true);
        MultiFormatReader reader=new MultiFormatReader();
        try { return reader.decode(new BinaryBitmap(new HybridBinarizer(pixels)),hints).getText(); }
        finally { reader.reset(); }
    }
    static String bitmap(Bitmap bitmap) throws Exception {
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
        return decode(new RGBLuminanceSource(w,h,pixels));
    }
    static String preview(byte[] data,int width,int height) throws Exception {
        return decode(new PlanarYUVLuminanceSource(data,width,height,0,0,width,height,false));
    }
}
