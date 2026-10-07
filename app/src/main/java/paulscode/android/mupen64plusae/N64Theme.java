/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * The second screen's look: bright, chunky, toy-like buttons in the four classic controller
 * colours on a cream background with confetti, and a game-cartridge header.
 */
public final class N64Theme
{
    private static final String TAG = "N64Theme";

    public static final int INK = 0xFF161628;
    public static final int CREAM = 0xFFF5ECD6;
    public static final int[] BLUE = {0xFF285FE1, 0xFF123296};
    public static final int[] RED = {0xFFE6282D, 0xFF961216};
    public static final int[] YELLOW = {0xFFFFCD1E, 0xFFBE8200};
    public static final int[] GREEN = {0xFF28AF46, 0xFF0F6928};
    public static final int[] PAPER = {0xFFFFF8E6, 0xFFC9BC9A};
    /** Order the menu buttons take their colours in. */
    public static final int[][] CYCLE = {BLUE, RED, YELLOW, GREEN};

    private N64Theme() {}

    private static Typeface sFont;

    /** Lilita One (SIL Open Font License, assets/fonts/LilitaOne-OFL.txt): chunky cartoon letters. */
    public static Typeface font(Context context)
    {
        if (sFont == null) {
            try {
                sFont = Typeface.createFromAsset(context.getAssets(), "fonts/LilitaOne-Regular.ttf");
            } catch (Exception e) {
                Log.w(TAG, "Couldn't load the font", e);
                sFont = Typeface.DEFAULT_BOLD;
            }
        }
        return sFont;
    }

    private static int shade(int color, float k)
    {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((color & 0xFF) * k));
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------------------------------------
    // A raised, glossy button: dark side underneath, gradient face, shine, thick outline
    // ---------------------------------------------------------------------------------------------

    public static final class ChunkyDrawable extends Drawable
    {
        private final int mFace, mSide;
        private final float mRadius, mDepth, mStroke, mDp;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private boolean mFocused, mPressed;

        public ChunkyDrawable(int[] colors, float dp, float radiusDp)
        {
            mFace = colors[0];
            mSide = colors[1];
            mDp = dp;
            mRadius = radiusDp * dp;
            mDepth = 6 * dp;
            mStroke = 2 * dp;
        }

        /** Room the drawing needs below the face (for padding the content). */
        public int depth() { return Math.round(mDepth); }

        @Override
        public boolean isStateful() { return true; }

        @Override
        protected boolean onStateChange(int[] state)
        {
            boolean focused = false, pressed = false;
            for (int s : state) {
                if (s == android.R.attr.state_focused) focused = true;
                if (s == android.R.attr.state_pressed) pressed = true;
            }
            if (focused == mFocused && pressed == mPressed) return false;
            mFocused = focused;
            mPressed = pressed;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(@NonNull Canvas c)
        {
            Rect b = getBounds();
            float ring = 5 * mDp;
            float l = b.left + ring, t = b.top + ring, r = b.right - ring, bottom = b.bottom - ring;
            float faceBottom = bottom - mDepth;
            float push = mPressed ? mDepth * 0.7f : 0;

            // Focus ring (controller selection)
            if (mFocused) {
                mPaint.setShader(null);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(3 * mDp);
                mPaint.setColor(0xFFFFFFFF);
                mR.set(b.left + 1.5f * mDp, b.top + 1.5f * mDp, b.right - 1.5f * mDp, b.bottom - 1.5f * mDp);
                c.drawRoundRect(mR, mRadius + ring, mRadius + ring, mPaint);
                mPaint.setStrokeWidth(1.2f * mDp);
                mPaint.setColor(INK);
                mR.inset(-1.8f * mDp, -1.8f * mDp);
                c.drawRoundRect(mR, mRadius + ring + 2 * mDp, mRadius + ring + 2 * mDp, mPaint);
            }

            // Shadow + side
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x30000000);
            mR.set(l + 2 * mDp, t + mDepth + 2 * mDp, r + 2 * mDp, bottom + 2 * mDp);
            c.drawRoundRect(mR, mRadius, mRadius, mPaint);
            mR.set(l, t + mDepth, r, bottom);
            mPaint.setColor(mSide);
            c.drawRoundRect(mR, mRadius, mRadius, mPaint);
            outline(c, mR);

            // Face
            mR.set(l, t + push, r, faceBottom + push);
            mPaint.setShader(new LinearGradient(0, mR.top, 0, mR.bottom, shade(mFace, 1.1f), shade(mFace, 0.86f), Shader.TileMode.CLAMP));
            c.drawRoundRect(mR, mRadius, mRadius, mPaint);
            mPaint.setShader(null);
            // Shine on the top part
            RectF shine = new RectF(mR.left + 6 * mDp, mR.top + 4 * mDp, mR.right - 6 * mDp, mR.top + mR.height() * 0.42f);
            mPaint.setColor(0x46FFFFFF);
            c.drawRoundRect(shine, mRadius * 0.8f, mRadius * 0.8f, mPaint);
            outline(c, mR);
        }

        private void outline(Canvas c, RectF r)
        {
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(mStroke);
            mPaint.setColor(INK);
            RectF in = new RectF(r);
            in.inset(mStroke / 2, mStroke / 2);
            c.drawRoundRect(in, mRadius, mRadius, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------------------------------------
    // A round glossy button (the floating search / add buttons)
    // ---------------------------------------------------------------------------------------------

    public static final class OrbDrawable extends Drawable
    {
        private final int mFace, mSide;
        private final float mDp;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mFocused, mPressed;

        public OrbDrawable(int[] colors, float dp) { mFace = colors[0]; mSide = colors[1]; mDp = dp; }

        @Override public boolean isStateful() { return true; }

        @Override
        protected boolean onStateChange(int[] state)
        {
            boolean focused = false, pressed = false;
            for (int s : state) {
                if (s == android.R.attr.state_focused) focused = true;
                if (s == android.R.attr.state_pressed) pressed = true;
            }
            if (focused == mFocused && pressed == mPressed) return false;
            mFocused = focused;
            mPressed = pressed;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(@NonNull Canvas c)
        {
            Rect b = getBounds();
            float depth = 5 * mDp;
            float r = Math.min(b.width(), b.height() - depth) / 2f - 3 * mDp;
            float cx = b.exactCenterX(), cy = b.top + 3 * mDp + r;
            float push = mPressed ? depth * 0.7f : 0;
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x38000000);
            c.drawCircle(cx + 2 * mDp, cy + depth + 2 * mDp, r, mPaint);
            mPaint.setColor(mSide);
            c.drawCircle(cx, cy + depth, r, mPaint);
            ring(c, cx, cy + depth, r);
            mPaint.setShader(new RadialGradient(cx, cy + push - r * 0.3f, r * 1.3f, shade(mFace, 1.18f), shade(mFace, 0.85f), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy + push, r, mPaint);
            mPaint.setShader(null);
            mPaint.setColor(0x40FFFFFF);
            c.drawOval(new RectF(cx - r * 0.5f, cy + push - r * 0.86f, cx + r * 0.5f, cy + push - r * 0.38f), mPaint);
            ring(c, cx, cy + push, r);
            if (mFocused) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(3 * mDp);
                mPaint.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy + depth / 2, r + 3.5f * mDp, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }
        }

        private void ring(Canvas c, float cx, float cy, float r)
        {
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2 * mDp);
            mPaint.setColor(INK);
            c.drawCircle(cx, cy, r - mDp, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------------------------------------------------
    // Background: cream, confetti, a big star, a faint gamepad, colour bands in the corner
    // ---------------------------------------------------------------------------------------------

    public static final class BackgroundDrawable extends Drawable
    {
        private final float mDp;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();

        public BackgroundDrawable(float dp) { mDp = dp; }

        @Override
        public void draw(@NonNull Canvas c)
        {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            c.drawColor(CREAM);
            int[] palette = {BLUE[0], RED[0], YELLOW[0], GREEN[0]};
            Random rnd = new Random(5);
            for (int i = 0; i < 38; i++) {
                float x = rnd.nextFloat() * w, y = 60 * mDp + rnd.nextFloat() * (h - 60 * mDp);
                float s = (6 + rnd.nextInt(9)) * mDp;
                mPaint.setColor((palette[rnd.nextInt(4)] & 0x00FFFFFF) | 0x46000000);
                float k = rnd.nextFloat();
                if (k < 0.45f) {
                    double a = rnd.nextDouble() * Math.PI * 2;
                    mPath.reset();
                    for (int p = 0; p < 3; p++) {
                        float px = x + s * (float) Math.cos(a + p * 2.094), py = y + s * (float) Math.sin(a + p * 2.094);
                        if (p == 0) mPath.moveTo(px, py); else mPath.lineTo(px, py);
                    }
                    mPath.close();
                    c.drawPath(mPath, mPaint);
                } else if (k < 0.8f) {
                    c.drawRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, mPaint);
                } else {
                    c.drawCircle(x, y, s / 2, mPaint);
                }
            }
            // Faint generic gamepad
            float gx = w * 0.72f, gy = h * 0.80f, gs = 70 * mDp;
            mPaint.setColor(0x379696A0);
            c.drawRoundRect(new RectF(gx - gs, gy - gs * 0.45f, gx + gs, gy + gs * 0.35f), gs * 0.4f, gs * 0.4f, mPaint);
            c.drawOval(new RectF(gx - gs * 1.15f, gy - gs * 0.3f, gx - gs * 0.35f, gy + gs * 0.9f), mPaint);
            c.drawOval(new RectF(gx + gs * 0.35f, gy - gs * 0.3f, gx + gs * 1.15f, gy + gs * 0.9f), mPaint);
            // Stars
            star(c, w * 0.36f, h * 0.90f, 30 * mDp, (YELLOW[0] & 0x00FFFFFF) | 0x96000000);
            star(c, w * 0.12f, h * 0.70f, 14 * mDp, (RED[0] & 0x00FFFFFF) | 0x5A000000);
            // Corner bands
            mPaint.setColor((RED[0] & 0x00FFFFFF) | 0xC8000000);
            mPath.reset();
            mPath.moveTo(w * 0.55f, h); mPath.lineTo(w, h * 0.80f); mPath.lineTo(w, h * 0.86f); mPath.lineTo(w * 0.68f, h);
            mPath.close();
            c.drawPath(mPath, mPaint);
            mPaint.setColor((BLUE[0] & 0x00FFFFFF) | 0xC8000000);
            mPath.reset();
            mPath.moveTo(w * 0.68f, h); mPath.lineTo(w, h * 0.86f); mPath.lineTo(w, h);
            mPath.close();
            c.drawPath(mPath, mPaint);
        }

        private void star(Canvas c, float cx, float cy, float r, int color)
        {
            mPath.reset();
            for (int i = 0; i < 10; i++) {
                float rr = i % 2 == 0 ? r : r * 0.45f;
                double a = -Math.PI / 2 + i * Math.PI / 5;
                float x = cx + rr * (float) Math.cos(a), y = cy + rr * (float) Math.sin(a);
                if (i == 0) mPath.moveTo(x, y); else mPath.lineTo(x, y);
            }
            mPath.close();
            mPaint.setColor(color);
            c.drawPath(mPath, mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    // ---------------------------------------------------------------------------------------------
    // Header: a game-cartridge top with a black label, the four colour squares, the name and
    // rainbow stripes
    // ---------------------------------------------------------------------------------------------

    public static final class HeaderView extends View
    {
        private final float mDp;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final String mTitle;

        public HeaderView(Context context, String title)
        {
            super(context);
            mDp = context.getResources().getDisplayMetrics().density;
            mTitle = title;
            mText.setTypeface(font(context));
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float d = mDp, w = getWidth(), h = getHeight();
            RectF cart = new RectF(4 * d, 2 * d, w - 4 * d, h - 6 * d);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(0x40000000);
            c.drawRoundRect(new RectF(cart.left, cart.top + 4 * d, cart.right, cart.bottom + 4 * d), 10 * d, 10 * d, mPaint);
            mPaint.setColor(0xFF8C929C);
            c.drawRoundRect(cart, 10 * d, 10 * d, mPaint);
            mPaint.setColor(0xFFAFB4BE);
            c.drawRect(cart.left + 6 * d, cart.top + 2 * d, cart.right - 6 * d, cart.top + 7 * d, mPaint);
            stroke(c, cart, 10 * d, 2 * d);
            // Grip ridges
            mPaint.setColor(0xFF646973);
            for (int i = 0; i < 4; i++) {
                float x = cart.right - 14 * d - i * 9 * d;
                c.drawRoundRect(new RectF(x - 3 * d, cart.top + 12 * d, x + 3 * d, cart.bottom - 10 * d), 2 * d, 2 * d, mPaint);
            }
            // Label
            RectF label = new RectF(cart.left + 14 * d, cart.top + 9 * d, cart.right - 52 * d, cart.bottom - 9 * d);
            mPaint.setColor(0xFF14141A);
            c.drawRoundRect(label, 6 * d, 6 * d, mPaint);
            c.save();
            mPath.reset();
            mPath.addRoundRect(label, 6 * d, 6 * d, Path.Direction.CW);
            c.clipPath(mPath);
            int[] stripes = {RED[0], 0xFFFF8C14, YELLOW[0], GREEN[0], BLUE[0]};
            for (int i = 0; i < stripes.length; i++) {
                float x = label.right - 70 * d + i * 11 * d;
                mPath.reset();
                mPath.moveTo(x, label.bottom); mPath.lineTo(x + 9 * d, label.bottom);
                mPath.lineTo(x + 31 * d, label.top); mPath.lineTo(x + 22 * d, label.top);
                mPath.close();
                mPaint.setColor(stripes[i]);
                c.drawPath(mPath, mPaint);
            }
            c.restore();
            stroke(c, label, 6 * d, 1.5f * d);
            // Four squares
            float sq = 8.5f * d, sx = label.left + 10 * d, sy = label.centerY() - sq - d;
            int[] squares = {RED[0], GREEN[0], BLUE[0], YELLOW[0]};
            for (int i = 0; i < 4; i++) {
                mPaint.setColor(squares[i]);
                float x = sx + (i % 2) * (sq + 2 * d), y = sy + (i / 2) * (sq + 2 * d);
                c.drawRoundRect(new RectF(x, y, x + sq, y + sq), 2 * d, 2 * d, mPaint);
            }
            // Name
            float size = label.height() * 0.62f;
            mText.setTextSize(size);
            Paint.FontMetrics fm = mText.getFontMetrics();
            float base = label.centerY() - (fm.ascent + fm.descent) / 2;
            float tx = label.left + 34 * d;
            mText.setStyle(Paint.Style.STROKE);
            mText.setStrokeJoin(Paint.Join.ROUND);
            mText.setStrokeWidth(2.4f * d);
            mText.setColor(INK);
            c.drawText(mTitle, tx, base, mText);
            mText.setStyle(Paint.Style.FILL);
            mText.setColor(0xFFFFFFFF);
            c.drawText(mTitle, tx, base, mText);
        }

        private void stroke(Canvas c, RectF r, float radius, float width)
        {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(width);
            mPaint.setColor(INK);
            RectF in = new RectF(r);
            in.inset(width / 2, width / 2);
            c.drawRoundRect(in, radius, radius, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Text and icons with a dark outline
    // ---------------------------------------------------------------------------------------------

    /**
     * One line of chunky text with a dark outline (white letters stay readable on any colour).
     * Shrinks to fit its width instead of cutting the text.
     */
    public static class OutlineTextView extends View
    {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mStroke;
        private final int mOutline;
        private String mText = "";
        private int mColor = 0xFFFFFFFF;
        private boolean mCenter = true;

        public OutlineTextView(Context context, float strokeDp, int outline)
        {
            super(context);
            mStroke = strokeDp * context.getResources().getDisplayMetrics().density;
            mOutline = outline;
            mPaint.setTypeface(font(context));
            mPaint.setTextSize(16 * context.getResources().getDisplayMetrics().scaledDensity);
        }

        public void setText(CharSequence text) { mText = text != null ? text.toString() : ""; setContentDescription(mText); requestLayout(); invalidate(); }
        public void setTextSize(int unit, float size)
        {
            mPaint.setTextSize(android.util.TypedValue.applyDimension(unit, size, getResources().getDisplayMetrics()));
            requestLayout();
            invalidate();
        }
        public void setTextColor(int color) { mColor = color; invalidate(); }
        /** Centre the text (true) or start it at the left padding (false). */
        public void setCentered(boolean center) { mCenter = center; invalidate(); }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec)
        {
            Paint.FontMetrics fm = mPaint.getFontMetrics();
            int w = (int) Math.ceil(mPaint.measureText(mText) + mStroke) + getPaddingLeft() + getPaddingRight();
            int h = (int) Math.ceil(fm.descent - fm.ascent + mStroke) + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(w, widthSpec), resolveSize(h, heightSpec));
        }

        @Override
        protected void onDraw(Canvas c)
        {
            float avail = getWidth() - getPaddingLeft() - getPaddingRight() - mStroke;
            float size = mPaint.getTextSize(), tw = mPaint.measureText(mText);
            if (tw > avail && tw > 0) mPaint.setTextSize(size * avail / tw);
            Paint.FontMetrics fm = mPaint.getFontMetrics();
            float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
            float base = cy - (fm.ascent + fm.descent) / 2;
            float x;
            if (mCenter) {
                mPaint.setTextAlign(Paint.Align.CENTER);
                x = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
            } else {
                mPaint.setTextAlign(Paint.Align.LEFT);
                x = getPaddingLeft() + mStroke / 2;
            }
            if (mStroke > 0) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setStrokeWidth(mStroke);
                mPaint.setColor(mOutline);
                c.drawText(mText, x, base, mPaint);
            }
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mColor);
            c.drawText(mText, x, base, mPaint);
            mPaint.setTextSize(size);
        }
    }

    /** The icon in white with a dark outline, as a bitmap of {@code sizePx}. */
    @Nullable
    public static Drawable outlinedIcon(Context context, @Nullable Drawable src, int sizePx)
    {
        if (src == null || sizePx <= 0) return null;
        float d = context.getResources().getDisplayMetrics().density;
        int edge = Math.round(1.6f * d), pad = edge + 1;
        int full = sizePx + pad * 2;
        Bitmap bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Drawable icon = src.getConstantState() != null ? src.getConstantState().newDrawable(context.getResources()).mutate() : src.mutate();
        icon.setTintList(null);
        icon.setColorFilter(new PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN));
        // Outline: the icon in ink, nudged around a circle
        for (int a = 0; a < 16; a++) {
            int dx = Math.round(edge * (float) Math.cos(a * Math.PI / 8)), dy = Math.round(edge * (float) Math.sin(a * Math.PI / 8));
            icon.setBounds(pad + dx, pad + dy, pad + dx + sizePx, pad + dy + sizePx);
            icon.draw(c);
        }
        icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        icon.setBounds(pad, pad, pad + sizePx, pad + sizePx);
        icon.draw(c);
        return new BitmapDrawable(context.getResources(), bmp);
    }

    /** Simple shapes drawn the same way: "plus" or "power". */
    public static Drawable outlinedShape(Context context, String kind, int sizePx)
    {
        float d = context.getResources().getDisplayMetrics().density;
        float edge = 1.6f * d;
        int pad = Math.round(edge) + 2, full = sizePx + pad * 2;
        Bitmap bmp = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = full / 2f, s = sizePx;
        for (int pass = 0; pass < 2; pass++) {
            float e = pass == 0 ? edge : 0;
            p.setColor(pass == 0 ? INK : 0xFFFFFFFF);
            if ("update".equals(kind)) {
                // circular arrow
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(3.4f * d + 2 * e);
                float r = s * 0.34f;
                c.drawArc(new RectF(cx - r, cx - r, cx + r, cx + r), -30, 300, false, p);
                p.setStyle(Paint.Style.FILL);
                double a = Math.toRadians(-30);
                float ax = cx + r * (float) Math.cos(a), ay = cx + r * (float) Math.sin(a), k = s * 0.2f + e * 1.4f;
                Path tri = new Path();
                tri.moveTo(ax - k, ay - k * 0.2f); tri.lineTo(ax + k * 0.9f, ay - k * 0.35f); tri.lineTo(ax + k * 0.15f, ay + k * 0.85f);
                tri.close();
                c.drawPath(tri, p);
            } else if ("plus".equals(kind)) {
                p.setStyle(Paint.Style.FILL);
                float t = s * 0.13f + e, L = s * 0.4f + e;
                c.drawRoundRect(new RectF(cx - L, cx - t, cx + L, cx + t), 2 * d, 2 * d, p);
                c.drawRoundRect(new RectF(cx - t, cx - L, cx + t, cx + L), 2 * d, 2 * d, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeWidth(3.2f * d + 2 * e);
                float r = s * 0.36f;
                c.drawArc(new RectF(cx - r, cx - r, cx + r, cx + r), -55, 290, false, p);
                c.drawLine(cx, cx - s * 0.46f, cx, cx, p);
            }
        }
        return new BitmapDrawable(context.getResources(), bmp);
    }
}
