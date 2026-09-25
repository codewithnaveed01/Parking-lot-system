package com.parking.service;

import java.security.MessageDigest;
import java.util.Base64;

/** A validated receipt screenshot (PNG / JPEG / WebP, at most 1.5 MB) sent as a data: URL. */
public final class ReceiptImage {
    public static final int MAX_BYTES = 1_500_000;
    private final String contentType, sha256;
    private final byte[] data;
    private ReceiptImage(String contentType, byte[] data, String sha256) { this.contentType = contentType; this.data = data; this.sha256 = sha256; }
    public String contentType() { return contentType; }
    public byte[] data() { return data; }
    public String sha256() { return sha256; }

    /** Returns null when no image was sent. */
    public static ReceiptImage fromDataUrl(String dataUrl) {
        if (dataUrl == null || dataUrl.trim().isEmpty()) return null;
        String s = dataUrl.trim();
        int comma = s.indexOf(',');
        if (!s.startsWith("data:") || comma < 0 || !s.substring(0, comma).endsWith(";base64"))
            throw new ParkingException("Upload the receipt as a PNG, JPG or WebP image");
        String type = s.substring(5, comma - 7).toLowerCase();
        byte[] data;
        try { data = Base64.getDecoder().decode(s.substring(comma + 1)); }
        catch (IllegalArgumentException e) { throw new ParkingException("The receipt image is damaged. Please upload it again"); }
        if (data.length < 100) throw new ParkingException("The receipt image is empty");
        if (data.length > MAX_BYTES) throw new ParkingException("The receipt image is too large (max 1.5 MB)", 413);
        boolean png = data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        boolean jpg = data[0] == (byte) 0xFF && data[1] == (byte) 0xD8;
        boolean webp = data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        String actual = png ? "image/png" : jpg ? "image/jpeg" : webp ? "image/webp" : null;
        if (actual == null || !(type.equals(actual) || (jpg && type.equals("image/jpg"))))
            throw new ParkingException("Upload the receipt as a PNG, JPG or WebP image");
        return new ReceiptImage(actual, data, sha256(data));
    }
    static String sha256(byte[] data) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
