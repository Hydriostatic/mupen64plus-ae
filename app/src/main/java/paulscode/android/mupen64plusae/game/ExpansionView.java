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
    /** Showing the map page (the expansion's map_screen) instead of the tiles page. */
    private boolean mOnMap;
    private final RectF mTitleRect = new RectF(), mMapBtnRect = new RectF();
    private final RectF[] mTabRects;
    private final float[] mTrailX = new float[4], mTrailY = new float[4];
    private int mTrailCount = 0;
    private Expansion.MapImage mTrailMap;
    private final android.graphics.Path mPath = new android.graphics.Path();
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
        mOnMap = expansion.mapScreen != null;
        int tabs = expansion.mapScreen != null ? expansion.mapScreen.tabs.size() : 0;
        mTabRects = new RectF[tabs];
        for (int i = 0; i < tabs; i++) mTabRects[i] = new RectF();
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
        if (mOnMap && mExp.mapScreen != null) {
            drawMapScreen(c, w, h);
            return;
        }
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

        mTitleRect.set(pad, signTop, w - pad, signBottom);

        // Buttons (plus "Map" when the expansion has a map page)
        float gap = pad;
        boolean map = mExp.mapScreen != null;
        float bw = (inner - gap * (map ? 2 : 1)) / (map ? 3 : 2);
        float x0 = pad;
        if (map) {
            mMapBtnRect.set(x0, btnTop, x0 + bw, btnTop + btnH);
            piece(c, mExp.buttonImage, mMapBtnRect.left, mMapBtnRect.top, mMapBtnRect.right, mMapBtnRect.bottom, mExp.panel);
            text(c, "MAPA", mMapBtnRect.centerX(), mMapBtnRect.centerY(), btnH * 0.4f, mExp.text, bw * 0.8f, Paint.Align.CENTER);
            x0 += bw + gap;
        } else {
            mMapBtnRect.setEmpty();
        }
        mMenuRect.set(x0, btnTop, x0 + bw, btnTop + btnH);
        mQuitRect.set(x0 + bw + gap, btnTop, w - pad, btnTop + btnH);
        piece(c, mExp.buttonImage, mMenuRect.left, mMenuRect.top, mMenuRect.right, mMenuRect.bottom, mExp.panel);
        piece(c, mExp.buttonImage, mQuitRect.left, mQuitRect.top, mQuitRect.right, mQuitRect.bottom, mExp.panel);
        text(c, "OPÇÕES", mMenuRect.centerX(), mMenuRect.centerY(), btnH * 0.4f, mExp.text, bw * 0.8f, Paint.Align.CENTER);
        text(c, "SALVAR E SAIR", mQuitRect.centerX(), mQuitRect.centerY(), btnH * 0.4f, mExp.text, bw * 0.8f, Paint.Align.CENTER);
    }

    // ---------------------------------------------------------------------------------------------
    // Map page: the template picture at its own proportions, slots, tabs and a map that follows
    // the player (the player's arrow stays in the middle; the map slides under it)
    // ---------------------------------------------------------------------------------------------

    private float mTs, mTx, mTy; // template scale and offset on the view

    private float tx(float x) { return mTx + x * mTs; }
    private float ty(float y) { return mTy + y * mTs; }

    private void drawMapScreen(Canvas c, float w, float h)
    {
        Expansion.MapScreen ms = mExp.mapScreen;
        c.drawColor(ms.fill);
        Bitmap tpl = mExp.image(ms.template);
        if (tpl == null) {
            text(c, ms.template + "?", w / 2, h / 2, h * 0.03f, mExp.label, w * 0.8f, Paint.Align.CENTER);
            return;
        }
        // Keep the template's own proportions: fit, centre, fill the rest
        mTs = Math.min(w / tpl.getWidth(), h / tpl.getHeight());
        mTx = (w - tpl.getWidth() * mTs) / 2;
        mTy = (h - tpl.getHeight() * mTs) / 2;
        mBox.set(mTx, mTy, mTx + tpl.getWidth() * mTs, mTy + tpl.getHeight() * mTs);
        mPaint.setAlpha(255);
        c.drawBitmap(tpl, null, mBox, mPaint);

        RectF area = new RectF(tx(ms.mapRect[0]), ty(ms.mapRect[1]), tx(ms.mapRect[2]), ty(ms.mapRect[3]));
        if (mConfirmQuit) {
            drawConfirm(c, area.left, area.top, area.right, area.bottom);
        } else if (!mValid) {
            text(c, "Esperando o jogo…", area.centerX(), area.centerY(), area.height() * 0.06f, mExp.label, area.width() * 0.8f, Paint.Align.CENTER);
        } else {
            drawMap(c, ms, tpl, area);
        }

        // World name, two lines: first word / the rest
        String name = (mValid ? mReader.format(ms.titleValue) : mExp.game).toUpperCase(Locale.US).trim();
        int sp = name.indexOf(' ');
        String l1 = sp > 0 ? name.substring(0, sp) : name, l2 = sp > 0 ? name.substring(sp + 1) : "";
        int n = mExp.titleColors.length;
        float size = ms.titleSize * mTs, cx = tx(ms.titleX), cy = ty(ms.titleY);
        if (l2.isEmpty()) {
            text(c, l1, cx, cy, size, n > 0 ? mExp.titleColors[0] : mExp.text, ms.titleW * mTs, Paint.Align.CENTER);
        } else {
            text(c, l1, cx, cy - size * 0.52f, size, n > 0 ? mExp.titleColors[0] : mExp.text, ms.titleW * mTs, Paint.Align.CENTER);
            text(c, l2, cx, cy + size * 0.52f, size, n > 1 ? mExp.titleColors[1] : mExp.text, ms.titleW * mTs, Paint.Align.CENTER);
        }
        mTitleRect.set(cx - ms.titleW * mTs / 2, cy - size * 1.1f, cx + ms.titleW * mTs / 2, cy + size * 1.1f);

        // Slots: icon + value in the template's circles
        if (mValid) {
            for (Expansion.Slot sl : ms.slots) {
                float r = sl.r * mTs, x = tx(sl.x), y = ty(sl.y);
                Bitmap icon = mExp.image(sl.icon);
                if (icon != null) {
                    fit(icon, x, y - r * 0.14f, r * 1.1f);
                    c.drawBitmap(icon, null, mBox, mPaint);
                }
                text(c, mReader.format(sl.value), x, y + r * 0.56f, r * 0.42f, mExp.text, r * 1.7f, Paint.Align.CENTER);
            }
        }

        // Tabs
        for (int i = 0; i < ms.tabs.size(); i++) {
            Expansion.Tab t = ms.tabs.get(i);
            RectF r = mTabRects[i];
            r.set(tx(t.x), ty(t.y), tx(t.x + t.w), ty(t.y + t.h));
            boolean on = "screen:map".equals(t.action);
            text(c, t.label.toUpperCase(Locale.US), r.centerX(), r.centerY(), r.height() * 0.27f,
                    on ? mExp.text : mExp.label, r.width() * 0.84f, Paint.Align.CENTER);
            if (on) {
                mPaint.setColor(mExp.accent);
                float uw = r.width() * 0.36f, uy = r.centerY() + r.height() * 0.28f;
                c.drawRoundRect(r.centerX() - uw, uy, r.centerX() + uw, uy + r.height() * 0.045f, 4, 4, mPaint);
            }
        }
    }

    private void drawMap(Canvas c, Expansion.MapScreen ms, Bitmap tpl, RectF area)
    {
        Long mapId = mReader.number(ms.mapValue);
        Expansion.MapImage mi = mapId != null ? ms.imageFor(mapId) : null;
        if (mi == null && ms.maps.size() == 1 && ms.maps.get(0).ids.length == 0) mi = ms.maps.get(0);
        Bitmap map = mi != null ? mExp.image(mi.image) : null;
        if (map == null) {
            text(c, "Sem mapa desta área", area.centerX(), area.centerY(), area.height() * 0.06f, mExp.label, area.width() * 0.8f, Paint.Align.CENTER);
            return;
        }

        mShownMap = mi;
        mShownMapW = map.getWidth();
        mShownMapH = map.getHeight();

        // Player position on the map picture (map pixels), if known
        Long gx = mReader.number(ms.xValue), gz = mReader.number(ms.zValue);
        double[] aff = calibration(mi);
        boolean havePos = !mCalibrating && aff != null && gx != null && gz != null;
        float px = havePos ? (float) (aff[0] * gx + aff[1] * gz + aff[2]) : mi.centerX * map.getWidth();
        float py = havePos ? (float) (aff[3] * gx + aff[4] * gz + aff[5]) : mi.centerY * map.getHeight();

        if (mi != mTrailMap) { mTrailCount = 0; mTrailMap = mi; }
        if (havePos) {
            float lx = mTrailCount > 0 ? mTrailX[mTrailCount - 1] : Float.NaN, ly = mTrailCount > 0 ? mTrailY[mTrailCount - 1] : Float.NaN;
            if (mTrailCount == 0 || Math.hypot(px - lx, py - ly) > map.getWidth() * 0.012f) {
                if (mTrailCount == mTrailX.length) {
                    System.arraycopy(mTrailX, 1, mTrailX, 0, mTrailCount - 1);
                    System.arraycopy(mTrailY, 1, mTrailY, 0, mTrailCount - 1);
                    mTrailCount--;
                }
                mTrailX[mTrailCount] = px; mTrailY[mTrailCount] = py; mTrailCount++;
            }
        }

        float k = Math.min(area.width() / map.getWidth(), area.height() / map.getHeight());
        float cx = area.centerX(), cy = area.centerY();
        float ox, oy;
        if (mCalibrating) {
            // Whole map, no zoom, so the player's spot can be tapped
            ox = cx - map.getWidth() * k / 2;
            oy = cy - map.getHeight() * k / 2;
        } else {
            k *= ms.zoom;
            ox = cx - px * k;
            oy = cy - py * k;
        }
        mMapK = k; mMapOx = ox; mMapOy = oy;
        mMapArea.set(area);

        // Draw the map clipped to the parchment (mask image, else the rectangle)
        int layer = c.saveLayer(area.left, area.top, area.right, area.bottom, null);
        c.clipRect(area);
        mBox.set(ox, oy, ox + map.getWidth() * k, oy + map.getHeight() * k);
        mPaint.setAlpha(255);
        c.drawBitmap(map, null, mBox, mPaint);
        Bitmap mask = mExp.image(ms.mask);
        if (mask != null) {
            Paint mp = new Paint(Paint.FILTER_BITMAP_FLAG);
            mp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
            mBox.set(mTx, mTy, mTx + tpl.getWidth() * mTs, mTy + tpl.getHeight() * mTs);
            c.drawBitmap(mask, null, mBox, mp);
        }
        c.restoreToCount(layer);

        if (mCalibrating) {
            String msg = gx == null || gz == null ? "Calibrar: a posição do jogador não pôde ser lida"
                    : "Calibrar (" + (mCalPoints.size() + 1) + "/3): toque onde o personagem está";
            banner(c, msg, area);
            return;
        }
        if (!havePos) {
            banner(c, gx == null || gz == null ? "Posição do jogador: não encontrada"
                    : "Segure o mapa para calibrar", area);
            return;
        }

        // Trail (older = fainter), then the arrow
        for (int i = 0; i < mTrailCount - 1; i++) {
            mPaint.setColor(0xFFFFFFFF);
            mPaint.setAlpha(90 + 50 * i);
            c.drawCircle(ox + mTrailX[i] * k, oy + mTrailY[i] * k, area.width() * (0.008f + 0.002f * i), mPaint);
        }
        mPaint.setAlpha(255);
        float r = area.width() * 0.042f;
        mPaint.setColor(0x50FFFFFF);
        c.drawCircle(cx, cy, r, mPaint);
        Long yaw = mReader.number(ms.yawValue);
        if (yaw == null) {
            // Direction unknown: a round pin
            mPaint.setColor(0xFFE8432E);
            c.drawCircle(cx, cy, r * 0.55f, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(r * 0.14f);
            mPaint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r * 0.55f, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            return;
        }
        float deg = (ms.yawClockwise ? yaw : -yaw) + ms.yawOffset;
        c.save();
        c.rotate(deg, cx, cy);
        mPath.reset();
        mPath.moveTo(cx + r * 0.95f, cy);
        mPath.lineTo(cx - r * 0.6f, cy - r * 0.65f);
        mPath.lineTo(cx - r * 0.3f, cy);
        mPath.lineTo(cx - r * 0.6f, cy + r * 0.65f);
        mPath.close();
        mPaint.setColor(0xFFE8432E);
        c.drawPath(mPath, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeWidth(r * 0.12f);
        mPaint.setColor(0xFFFFFFFF);
        c.drawPath(mPath, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    // --- Map calibration: hold the map, then tap where the player is, in 3 places ---------------

    private boolean mCalibrating;
    private final java.util.List<double[]> mCalPoints = new java.util.ArrayList<>();
    private Expansion.MapImage mShownMap;
    private int mShownMapW, mShownMapH;
    private float mMapK, mMapOx, mMapOy;
    private final RectF mMapArea = new RectF();
    private final java.util.Map<Expansion.MapImage, double[]> mCalCache = new java.util.HashMap<>();

    private android.content.SharedPreferences prefs()
    {
        return getContext().getSharedPreferences("expansion_maps", Context.MODE_PRIVATE);
    }

    private String calKey(Expansion.MapImage mi) { return mExp.id + "|" + mi.image; }

    /** The user's own calibration for this map if there is one, else the expansion's. */
    private double[] calibration(Expansion.MapImage mi)
    {
        if (mCalCache.containsKey(mi)) return mCalCache.get(mi);
        double[] aff = mi.affine;
        String saved = prefs().getString(calKey(mi), null);
        if (saved != null) {
            try {
                String[] parts = saved.split(",");
                double[] v = new double[6];
                for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(parts[i]);
                aff = v;
            } catch (Exception ignored) {}
        }
        mCalCache.put(mi, aff);
        return aff;
    }

    private void startCalibration()
    {
        mCalibrating = true;
        mCalPoints.clear();
        invalidate();
    }

    private void calibrationTap(float x, float y)
    {
        Long gx = mReader.number(mExp.mapScreen.xValue), gz = mReader.number(mExp.mapScreen.zValue);
        if (mShownMap == null || gx == null || gz == null || mMapK <= 0) return;
        double px = (x - mMapOx) / mMapK, py = (y - mMapOy) / mMapK;
        mCalPoints.add(new double[]{gx, gz, px, py});
        if (mCalPoints.size() < 3) {
            android.widget.Toast.makeText(getContext(), "Ponto " + mCalPoints.size() + " salvo. Ande até outro lugar.",
                    android.widget.Toast.LENGTH_SHORT).show();
            invalidate();
            return;
        }
        double[] aff = Expansion.affine(mCalPoints.toArray(new double[0][]));
        mCalibrating = false;
        if (aff == null) {
            android.widget.Toast.makeText(getContext(), "Pontos muito próximos ou em linha. Tente de novo.",
                    android.widget.Toast.LENGTH_LONG).show();
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(i > 0 ? "," : "").append(aff[i]);
            prefs().edit().putString(calKey(mShownMap), sb.toString()).apply();
            mCalCache.put(mShownMap, aff);
            mTrailCount = 0;
            android.widget.Toast.makeText(getContext(), "Mapa calibrado", android.widget.Toast.LENGTH_SHORT).show();
        }
        invalidate();
    }

    private void banner(Canvas c, String msg, RectF area)
    {
        float bh = area.height() * 0.075f;
        mBox.set(area.left + area.width() * 0.06f, area.bottom - bh * 1.5f, area.right - area.width() * 0.06f, area.bottom - bh * 0.5f);
        mPaint.setColor(0xC0201005);
        c.drawRoundRect(mBox, bh * 0.3f, bh * 0.3f, mPaint);
        text(c, msg, mBox.centerX(), mBox.centerY(), bh * 0.45f, 0xFFFFFFFF, mBox.width() * 0.92f, Paint.Align.CENTER);
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

    private final Runnable mLongPress = this::onLongPress;
    private final Runnable mMapLongPress = () -> { mLongPressed = true; startCalibration(); };

    private void onLongPress()
    {
        mLongPressed = true;
        MemorySnapshot.save(getContext(), mExp.id);
    }
    private boolean mLongPressed;

    @Override
    public boolean onTouchEvent(MotionEvent e)
    {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Hold the world name: save a memory snapshot (for writing expansions)
                mLongPressed = false;
                if (mTitleRect.contains(x, y)) mHandler.postDelayed(mLongPress, 800);
                else if (mOnMap && !mConfirmQuit && mMapArea.contains(x, y)) mHandler.postDelayed(mMapLongPress, 800);
                return true;
            case MotionEvent.ACTION_CANCEL:
                mHandler.removeCallbacks(mLongPress);
                mHandler.removeCallbacks(mMapLongPress);
                return true;
            case MotionEvent.ACTION_UP:
                mHandler.removeCallbacks(mLongPress);
                mHandler.removeCallbacks(mMapLongPress);
                if (mLongPressed) return true;
                break;
            default:
                return super.onTouchEvent(e);
        }
        if (mOnMap && mCalibrating && mMapArea.contains(x, y)) {
            calibrationTap(x, y);
            return true;
        }
        if (mOnMap && mExp.mapScreen != null && !mConfirmQuit) {
            for (int i = 0; i < mTabRects.length; i++) {
                if (!mTabRects[i].contains(x, y)) continue;
                String action = mExp.mapScreen.tabs.get(i).action;
                mCalibrating = false;
                if ("screen:main".equals(action)) mOnMap = false;
                else if ("menu".equals(action)) { if (mOpenMenu != null) mOpenMenu.run(); }
                else if ("save_quit".equals(action)) mConfirmQuit = true;
                invalidate();
                performClick();
                return true;
            }
            return true;
        }
        if (!mOnMap && mMapBtnRect.contains(x, y)) {
            mOnMap = true;
            invalidate();
            return true;
        }
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
        mHandler.removeCallbacks(mLongPress);
        super.onDetachedFromWindow();
    }
}
