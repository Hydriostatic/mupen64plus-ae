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
package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Second-screen panel drawn from an installed expansion (.exp): a title sign, a grid of tiles,
 * an optional bar and two buttons (emulator menu, save and quit). All values come from the game's
 * memory as the expansion describes; nothing here is game-specific.
 */
public class ExpansionView extends View
{
    private static final long REFRESH_MS = 250;

    private final Expansion mExp;
    private final Expansion.Reader mReader;
    private final Runnable mOpenMenu, mSaveAndQuit;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF(), mBox = new RectF();
    private final RectF mMenuRect = new RectF(), mQuitRect = new RectF();
    private final RectF mYesRect = new RectF(), mNoRect = new RectF();

    private boolean mRunning, mValid, mConfirmQuit;
    private long mLastTick;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            long now = SystemClock.elapsedRealtime();
            if (mLastTick != 0 && mValid) mReader.sessionMs += now - mLastTick;
            mLastTick = now;
            if (isShown()) {
                mValid = mReader.refresh();
                invalidate();
            }
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public ExpansionView(Context context, Expansion expansion, Runnable openMenu, Runnable saveAndQuit)
    {
        super(context);
        mExp = expansion;
        mReader = expansion.new Reader();
        mOpenMenu = openMenu;
        mSaveAndQuit = saveAndQuit;
        Typeface tf = expansion.typeface(context.getCacheDir());
        if (tf == null) tf = Typeface.create("sans-serif-black", Typeface.BOLD);
        mText.setTypeface(tf);
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------------------------

    private void nine(Canvas c, Expansion.Nine n, Bitmap b, RectF d, float scale)
    {
        int bw = b.getWidth(), bh = b.getHeight();
        int[] sx = {0, n.left, bw - n.right, bw};
        int[] sy = {0, n.top, bh - n.bottom, bh};
        float[] dx = {d.left, d.left + n.left * scale, d.right - n.right * scale, d.right};
        float[] dy = {d.top, d.top + n.top * scale, d.bottom - n.bottom * scale, d.bottom};
        mPaint.setAlpha(255);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                if (dx[x + 1] <= dx[x] || dy[y + 1] <= dy[y] || sx[x + 1] <= sx[x] || sy[y + 1] <= sy[y]) continue;
                mSrc.set(sx[x], sy[y], sx[x + 1], sy[y + 1]);
                mDst.set(dx[x], dy[y], dx[x + 1], dy[y + 1]);
                c.drawBitmap(b, mSrc, mDst, mPaint);
            }
        }
    }

    /** A piece: the expansion's 9-slice image if it has one, else a plain rounded panel. */
    private void piece(Canvas c, Expansion.Nine n, float l, float t, float r, float b, int color)
    {
        mBox.set(l, t, r, b);
        Bitmap bmp = n != null ? mExp.image(n.image) : null;
        if (bmp != null) {
            nine(c, n, bmp, mBox, (r - l) / bmp.getWidth());
            return;
        }
        float rad = Math.min(r - l, b - t) * 0.12f;
        mPaint.setColor(color);
        c.drawRoundRect(mBox, rad, rad, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(2, rad * 0.18f));
        mPaint.setColor(0x60000000);
        c.drawRoundRect(mBox, rad, rad, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
    }

    private float aspect(Expansion.Nine n, float fallback)
    {
        Bitmap b = n != null ? mExp.image(n.image) : null;
        return b != null ? (float) b.getHeight() / b.getWidth() : fallback;
    }

    @Override
    protected void onDraw(Canvas c)
    {
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float pad = w * 0.022f;

        // Background
        Bitmap bg = mExp.image(mExp.backgroundImage);
        if (bg != null) {
            mBox.set(0, 0, w, h);
            float s = Math.max(w / bg.getWidth(), h / bg.getHeight());
            float bw = bg.getWidth() * s, bh = bg.getHeight() * s;
            mBox.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            c.drawBitmap(bg, null, mBox, mPaint);
        } else {
            c.drawColor(mExp.background);
        }

        // Layout: sign | tiles | bar | buttons
        float inner = w - pad * 2;
        float signH = Math.min(h * 0.17f, inner * aspect(mExp.signImage, 0.16f));
        boolean hasBar = !mExp.bar.isEmpty();
        float barH = hasBar ? Math.min(h * 0.11f, inner * aspect(mExp.barImage, 0.1f)) : 0;
        float btnH = h * 0.09f;
        float signTop = pad, signBottom = signTop + signH;
        float btnTop = h - pad - btnH;
        float barBottom = btnTop - pad * 0.7f, barTop = barBottom - barH;
        float tilesTop = signBottom + pad * 0.7f;
        float tilesBottom = (hasBar ? barTop : btnTop) - pad * 0.7f;

        drawSign(c, pad, signTop, w - pad, signBottom, mValid ? mReader.format(mExp.title) : mExp.name);

        if (mConfirmQuit) {
            drawConfirm(c, pad, tilesTop, w - pad, tilesBottom);
        } else if (!mValid) {
            text(c, "Esperando o jogo…", w / 2, (tilesTop + tilesBottom) / 2, h * 0.035f, mExp.label, w * 0.8f, Paint.Align.CENTER);
        } else {
            drawTiles(c, pad, tilesTop, w - pad, tilesBottom);
            if (hasBar) drawBar(c, pad, barTop, w - pad, barBottom);
        }

        // Buttons
        float gap = pad;
        float bw = (inner - gap) / 2;
        mMenuRect.set(pad, btnTop, pad + bw, btnTop + btnH);
        mQuitRect.set(pad + bw + gap, btnTop, w - pad, btnTop + btnH);
        piece(c, mExp.buttonImage, mMenuRect.left, mMenuRect.top, mMenuRect.right, mMenuRect.bottom, mExp.panel);
        piece(c, mExp.buttonImage, mQuitRect.left, mQuitRect.top, mQuitRect.right, mQuitRect.bottom, mExp.panel);
        text(c, "OPÇÕES", mMenuRect.centerX(), mMenuRect.centerY(), btnH * 0.4f, mExp.text, bw * 0.8f, Paint.Align.CENTER);
        text(c, "SALVAR E SAIR", mQuitRect.centerX(), mQuitRect.centerY(), btnH * 0.4f, mExp.text, bw * 0.8f, Paint.Align.CENTER);
    }

    /** Title sign: each word gets the next of the theme's title colours. */
    private void drawSign(Canvas c, float l, float t, float r, float b, String title)
    {
        piece(c, mExp.signImage, l, t, r, b, mExp.panel);
        float h = b - t;
        String up = title.toUpperCase(Locale.US);
        float size = h * 0.5f;
        mText.setTextSize(size);
        float maxW = (r - l) * 0.8f, total = mText.measureText(up);
        if (total > maxW) { size *= maxW / total; mText.setTextSize(size); total = maxW; }
        float x = (l + r) / 2 - total / 2;
        float cy = (t + b) / 2;
        String[] words = up.split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = (i > 0 ? " " : "") + words[i];
            // Word 1 gets colour 1, word 2 colour 2, ...; extra words keep the last colour
            int n = mExp.titleColors.length;
            int color = n > 0 ? mExp.titleColors[Math.min(i, n - 1)] : mExp.text;
            text(c, word, x, cy, size, color, Float.MAX_VALUE, Paint.Align.LEFT);
            x += mText.measureText(word);
        }
    }

    private void drawTiles(Canvas c, float l, float t, float r, float b)
    {
        int n = mExp.tiles.size();
        if (n == 0) return;
        int cols = Math.min(mExp.columns, n);
        int rows = (n + cols - 1) / cols;
        float gap = (r - l) * 0.018f;
        float tw = ((r - l) - gap * (cols - 1)) / cols;
        float th = ((b - t) - gap * (rows - 1)) / rows;
        for (int i = 0; i < n; i++) {
            Expansion.Tile tile = mExp.tiles.get(i);
            float x = l + (i % cols) * (tw + gap), y = t + (i / cols) * (th + gap);
            piece(c, mExp.tileImage, x, y, x + tw, y + th, mExp.panel);
            // Where label and value go: the tile image can say (its own pixels), else fractions
            Bitmap tileBmp = mExp.tileImage != null ? mExp.image(mExp.tileImage.image) : null;
            float ts = tileBmp != null ? tw / tileBmp.getWidth() : 0;
            float labelCy = ts > 0 && mExp.tileImage.labelY > 0 ? y + mExp.tileImage.labelY * ts : y + th * 0.13f;
            float valueCy = ts > 0 && mExp.tileImage.valueY > 0 ? y + th - mExp.tileImage.valueY * ts : y + th * 0.85f;
            float lh = Math.min(th * 0.14f, tw * 0.13f);
            text(c, mReader.format(tile.label).toUpperCase(Locale.US), x + tw / 2, labelCy, lh, mExp.label, tw * 0.66f, Paint.Align.CENTER);
            Bitmap icon = mExp.image(tile.icon);
            float iconTop = labelCy + lh * 0.9f, iconBottom = valueCy - th * 0.1f;
            float is = Math.min(tw * 0.62f, iconBottom - iconTop);
            float cx = x + tw / 2, cy = (iconTop + iconBottom) / 2;
            if (icon != null) {
                fit(icon, cx, cy, is);
                c.drawBitmap(icon, null, mBox, mPaint);
            } else {
                mPaint.setColor(mExp.accent);
                c.drawCircle(cx, cy, is * 0.32f, mPaint);
            }
            text(c, mReader.format(tile.value), cx, valueCy, Math.min(th * 0.15f, tw * 0.16f), mExp.text, tw * 0.66f, Paint.Align.CENTER);
        }
    }

    private void drawBar(Canvas c, float l, float t, float r, float b)
    {
        piece(c, mExp.barImage, l, t, r, b, mExp.panel);
        float h = b - t;
        float x = l + h * 0.4f;
        Bitmap barBmp = mExp.barImage != null ? mExp.image(mExp.barImage.image) : null;
        if (barBmp != null && mExp.barImage.contentLeft > 0) x = l + mExp.barImage.contentLeft * (r - l) / barBmp.getWidth();
        if (!mExp.barLabel.isEmpty()) {
            text(c, mExp.barLabel.toUpperCase(Locale.US), x, (t + b) / 2, h * 0.32f, mExp.label, (r - l) * 0.3f, Paint.Align.LEFT);
            x += Math.min(mText.measureText(mExp.barLabel.toUpperCase(Locale.US)), (r - l) * 0.3f) + h * 0.4f;
        }
        int n = mExp.bar.size();
        float slot = (r - h * 0.3f - x) / n;
        for (int i = 0; i < n; i++) {
            Expansion.Tile item = mExp.bar.get(i);
            float sx = x + i * slot, cy = (t + b) / 2;
            float is = h * 0.62f;
            Bitmap icon = mExp.image(item.icon);
            if (icon != null) {
                fit(icon, sx + is / 2, cy, is);
                c.drawBitmap(icon, null, mBox, mPaint);
            } else {
                mPaint.setColor(mExp.accent);
                c.drawCircle(sx + is / 2, cy, is * 0.3f, mPaint);
            }
            text(c, mReader.format(item.value), sx + is * 1.1f, cy, h * 0.38f, mExp.text, slot - is * 1.2f, Paint.Align.LEFT);
        }
    }

    private void drawConfirm(Canvas c, float l, float t, float r, float b)
    {
        float h = b - t, w = r - l;
        piece(c, mExp.tileImage, l + w * 0.1f, t + h * 0.2f, r - w * 0.1f, b - h * 0.2f, mExp.panel);
        text(c, "Salvar e sair do jogo?", (l + r) / 2, t + h * 0.4f, h * 0.06f, mExp.text, w * 0.7f, Paint.Align.CENTER);
        float bw = w * 0.25f, bh = h * 0.12f, by = t + h * 0.55f;
        mYesRect.set((l + r) / 2 - bw - w * 0.02f, by, (l + r) / 2 - w * 0.02f, by + bh);
        mNoRect.set((l + r) / 2 + w * 0.02f, by, (l + r) / 2 + bw + w * 0.02f, by + bh);
        mPaint.setColor(0xFF3E8E2C);
        c.drawRoundRect(mYesRect, bh * 0.25f, bh * 0.25f, mPaint);
        mPaint.setColor(0xFF9C2A1E);
        c.drawRoundRect(mNoRect, bh * 0.25f, bh * 0.25f, mPaint);
        text(c, "SIM", mYesRect.centerX(), mYesRect.centerY(), bh * 0.45f, 0xFFFFFFFF, bw * 0.8f, Paint.Align.CENTER);
        text(c, "NÃO", mNoRect.centerX(), mNoRect.centerY(), bh * 0.45f, 0xFFFFFFFF, bw * 0.8f, Paint.Align.CENTER);
    }

    /** mBox = icon fitted (keeping its shape) in a square of side s around (cx, cy). */
    private void fit(Bitmap icon, float cx, float cy, float s)
    {
        float k = s / Math.max(icon.getWidth(), icon.getHeight());
        float iw = icon.getWidth() * k, ih = icon.getHeight() * k;
        mBox.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
    }

    /** Text with a dark outline, vertically centred on cy, shrunk to fit maxWidth. */
    private void text(Canvas c, String s, float x, float cy, float size, int color, float maxWidth, Paint.Align align)
    {
        if (s == null) return;
        mText.setTextSize(size);
        float tw = mText.measureText(s);
        if (tw > maxWidth && tw > 0) { size *= maxWidth / tw; mText.setTextSize(size); }
        mText.setTextAlign(align);
        Paint.FontMetrics fm = mText.getFontMetrics();
        float base = cy - (fm.ascent + fm.descent) / 2;
        mText.setStyle(Paint.Style.STROKE);
        mText.setStrokeJoin(Paint.Join.ROUND);
        mText.setStrokeWidth(size * 0.16f);
        mText.setColor(0xE0180C03);
        c.drawText(s, x, base, mText);
        mText.setStyle(Paint.Style.FILL);
        mText.setColor(color);
        c.drawText(s, x, base, mText);
        mText.setTextAlign(Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------------------------------------
    // Touch and lifecycle
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e)
    {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        if (mConfirmQuit) {
            if (mYesRect.contains(x, y)) { mConfirmQuit = false; if (mSaveAndQuit != null) mSaveAndQuit.run(); }
            else if (mNoRect.contains(x, y)) mConfirmQuit = false;
            invalidate();
            return true;
        }
        if (mMenuRect.contains(x, y)) {
            if (mOpenMenu != null) mOpenMenu.run();
        } else if (mQuitRect.contains(x, y)) {
            mConfirmQuit = true;
            invalidate();
        }
        performClick();
        return true;
    }

    @Override
    public boolean performClick()
    {
        return super.performClick();
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        mRunning = true;
        mLastTick = 0;
        mHandler.removeCallbacks(mRefresh);
        mHandler.post(mRefresh);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        mRunning = false;
        mHandler.removeCallbacks(mRefresh);
        super.onDetachedFromWindow();
    }
}
