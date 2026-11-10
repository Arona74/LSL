package io.arona74.lsl.dh;

/**
 * Colour arithmetic for dh_neutral_tints, on ARGB ints. Greys are taken the way DH averages
 * textures: in linear light, back to sRGB.
 */
public final class DhColors {
    private DhColors() {}

    private static final float[] TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** The same colour as a grey of equal brightness (relative luminance), alpha kept. */
    public static int grey(int argb) {
        float y = 0.2126f * TO_LINEAR[argb >> 16 & 0xFF] + 0.7152f * TO_LINEAR[argb >> 8 & 0xFF]
            + 0.0722f * TO_LINEAR[argb & 0xFF];
        int g = toSrgb(y);
        return argb & 0xFF000000 | g << 16 | g << 8 | g;
    }

    /** Channel-wise product of a colour and a tint, as DH tints (alpha from {@code argb}). */
    public static int multiply(int argb, int tint) {
        int r = (argb >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int g = (argb >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return argb & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int toSrgb(float linear) {
        double c = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(c * 255)));
    }
}
