package paulscode.android.mupen64plusae.game;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import paulscode.android.mupen64plusae.R;

/**
 * Every tunable of the Jet Force Gemini camera, editable by touch. During the game it sits on
 * the second screen and every change applies at once; it is also the settings screen reached
 * from Settings > Input. Changes are saved to the camera's settings file.
 */
public class JetForceGeminiCameraPanel extends FrameLayout
{
    private static final long STATUS_REFRESH_MS = 500;

    private static final int BG = 0xFF101418;
    private static final int CARD = 0xFF1C232B;
    private static final int ACCENT = 0xFF5FD3B5;
    private static final int TEXT = 0xFFECEFF1;
    private static final int MUTED = 0xFF90A4AE;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final boolean mLive;
    private final JetForceGeminiCameraSettings mSettings;
    private final TextView mStatus;
    private final Switch[] mSwitches = new Switch[JetForceGeminiCameraSettings.PARAMS.length];
    private final SeekBar[] mSeekBars = new SeekBar[JetForceGeminiCameraSettings.PARAMS.length];
    private final TextView[] mValueLabels = new TextView[JetForceGeminiCameraSettings.PARAMS.length];
    private boolean mUpdating = false;

    private final Runnable mStatusRefresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    /**
     * @param live True when shown inside the running game: changes go to the core at once.
     */
    public JetForceGeminiCameraPanel(Context context, boolean live)
    {
        super(context);
        mLive = live;
        mSettings = JetForceGeminiCameraSettings.load(context);
        setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(12), dp(16), dp(16));
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(context.getString(R.string.jfgPanel_title), 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        mStatus = text("", 13, ACCENT);
        mStatus.setPadding(0, dp(2), 0, dp(8));
        column.addView(mStatus);

        for (int i = 0; i < JetForceGeminiCameraSettings.PARAMS.length; i++)
            column.addView(buildRow(i), cardParams());

        Button reset = new Button(context);
        reset.setText(R.string.jfgPanel_reset);
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            mSettings.resetToDefaults();
            mSettings.save();
            refreshControls();
            if (mLive) JetForceGeminiCamera.applyAll(mSettings);
        });
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(8);
        resetParams.gravity = Gravity.CENTER_HORIZONTAL;
        column.addView(reset, resetParams);

        TextView note = text(context.getString(R.string.jfgPanel_note), 12, MUTED);
        note.setPadding(0, dp(10), 0, 0);
        column.addView(note);

        refreshControls();
        updateStatus();
    }

    private View buildRow(final int index)
    {
        final Context context = getContext();
        final JetForceGeminiCameraSettings.Param param = JetForceGeminiCameraSettings.PARAMS[index];

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(8));
        card.setBackground(bg);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);

        TextView title = text(context.getString(param.titleRes), 15, TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (param.type == JetForceGeminiCameraSettings.TYPE_SWITCH) {
            Switch toggle = new Switch(context);
            toggle.setThumbTintList(ColorStateList.valueOf(ACCENT));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (mUpdating) return;
                change(index, checked ? 1f : 0f, true);
            });
            header.addView(toggle);
            mSwitches[index] = toggle;
            card.setOnClickListener(v -> toggle.toggle());
        } else {
            TextView value = text("", 15, ACCENT);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(value);
            mValueLabels[index] = value;

            SeekBar bar = new SeekBar(context);
            bar.setMax(param.steps());
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (mUpdating || !fromUser) return;
                    change(index, param.min + progress * param.step, false);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    mSettings.save();
                }
            });
            card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mSeekBars[index] = bar;
        }

        if (param.summaryRes != 0) {
            TextView summary = text(context.getString(param.summaryRes), 12, MUTED);
            card.addView(summary);
        }
        return card;
    }

    private void change(int index, float value, boolean save)
    {
        mSettings.set(index, value);
        float stored = mSettings.get(index);
        if (mValueLabels[index] != null)
            mValueLabels[index].setText(JetForceGeminiCameraSettings.PARAMS[index].describe(stored));
        if (mLive)
            JetForceGeminiCamera.apply(index, stored);
        if (save)
            mSettings.save();
        if (index == 0)
            updateStatus();
    }

    private void refreshControls()
    {
        mUpdating = true;
        for (int i = 0; i < JetForceGeminiCameraSettings.PARAMS.length; i++) {
            JetForceGeminiCameraSettings.Param param = JetForceGeminiCameraSettings.PARAMS[i];
            float value = mSettings.get(i);
            if (mSwitches[i] != null)
                mSwitches[i].setChecked(value >= 0.5f);
            if (mSeekBars[i] != null)
                mSeekBars[i].setProgress(Math.round((value - param.min) / param.step));
            if (mValueLabels[i] != null)
                mValueLabels[i].setText(param.describe(value));
        }
        mUpdating = false;
    }

    private void updateStatus()
    {
        int res;
        if (!mLive) {
            res = R.string.jfgPanel_statusSettings;
        } else if (mSettings.get(0) < 0.5f) {
            res = R.string.jfgPanel_statusOff;
        } else {
            res = JetForceGeminiCamera.status() == 2 ? R.string.jfgPanel_statusActive
                    : R.string.jfgPanel_statusWaiting;
        }
        mStatus.setText(res);
    }

    @Override
    protected void onAttachedToWindow()
    {
        super.onAttachedToWindow();
        if (mLive) mHandler.post(mStatusRefresh);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        mHandler.removeCallbacks(mStatusRefresh);
        mSettings.save();
        super.onDetachedFromWindow();
    }

    private LinearLayout.LayoutParams cardParams()
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private TextView text(String value, int sp, int color)
    {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value)
    {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
