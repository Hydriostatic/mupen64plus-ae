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
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import paulscode.android.mupen64plusae.game.Expansion;
import paulscode.android.mupen64plusae.game.ExpansionImportActivity;
import paulscode.android.mupen64plusae.game.ExpansionManager;
import paulscode.android.mupen64plusae.game.SecondScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * Home of the game list on the second screen (BC): a button for each option (Settings,
 * Profiles, Tools, Help, About, ...). Options with sub-options open a popup window with them.
 * Floating buttons at the bottom open the search bar and add ROMs.
 *
 * Sized for small square-ish screens such as the AYN Thor's bottom panel (1080x1240).
 */
public class GalleryHomePanel extends FrameLayout
{
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFFB8B8B8;
    private static final int ACCENT = 0xFF3249BC;   // @color/blue1, the app's floating-button colour
    private static final int FOCUS = 0xFF5C78FF;

    private final GalleryActivity mActivity;
    private final float mDp;

    private LinearLayout mSearchBar;
    private EditText mSearchField;
    private FrameLayout mPopup;
    private LinearLayout mPopupList;
    private TextView mPopupTitle;
    private View mFirstButton;

    public GalleryHomePanel(GalleryActivity activity, Menu menu)
    {
        super(activity);
        mActivity = activity;
        mDp = activity.getResources().getDisplayMetrics().density;
        setId(R.id.galleryHomePanel);
        build(activity, menu);
    }

    private int dp(float v) { return Math.round(v * mDp); }

    // ---------------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------------

    private void build(Context ctx, Menu menu)
    {
        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(8), dp(8), dp(8), dp(8));
        addView(column, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Search bar (hidden until the magnifier button is pressed)
        mSearchBar = new LinearLayout(ctx);
        mSearchBar.setOrientation(LinearLayout.HORIZONTAL);
        mSearchBar.setGravity(Gravity.CENTER_VERTICAL);
        mSearchBar.setPadding(dp(10), 0, dp(4), 0);
        mSearchBar.setBackground(rounded(0xE6202020, dp(22), 0x55FFFFFF));
        mSearchBar.setVisibility(View.GONE);
        ImageView lens = new ImageView(ctx);
        lens.setImageResource(android.R.drawable.ic_menu_search);
        lens.setImageTintList(ColorStateList.valueOf(MUTED));
        mSearchBar.addView(lens, new LinearLayout.LayoutParams(dp(24), dp(24)));
        mSearchField = new EditText(ctx);
        mSearchField.setHint(R.string.actionSearchRoms);
        mSearchField.setHintTextColor(0xFF8A8A8A);
        mSearchField.setTextColor(TEXT);
        mSearchField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mSearchField.setSingleLine(true);
        mSearchField.setInputType(InputType.TYPE_CLASS_TEXT);
        mSearchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        mSearchField.setBackground(null);
        mSearchField.setPadding(dp(8), dp(10), dp(8), dp(10));
        mSearchField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { mActivity.setSearchQueryFromSecondScreen(s.toString()); }
        });
        mSearchBar.addView(mSearchField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = label(ctx, "✕", 18, MUTED, true);
        clear.setPadding(dp(10), dp(6), dp(10), dp(6));
        clear.setOnClickListener(v -> closeSearch());
        mSearchBar.addView(clear);
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        sbLp.bottomMargin = dp(8);
        column.addView(mSearchBar, sbLp);

        // Option buttons
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, 0, 0, dp(72)); // room for the floating buttons
        scroll.addView(grid, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<View> buttons = new ArrayList<>();
        MenuItem addRoms = null;
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) continue;
            if (item.getItemId() == R.id.menuItem_refreshRoms) {
                addRoms = item; // shown as a floating button instead
                continue;
            }
            buttons.add(optionButton(ctx, item.getIcon(), item.getTitle(), v -> onOption(item)));
        }
        Drawable expIcon = ctx.getDrawable(android.R.drawable.ic_menu_add);
        buttons.add(optionButton(ctx, expIcon, ctx.getString(R.string.expansions_title), v -> showExpansions()));
        Drawable exitIcon = ctx.getDrawable(android.R.drawable.ic_lock_power_off);
        buttons.add(optionButton(ctx, exitIcon, ctx.getString(R.string.secondScreen_exit), v -> mActivity.exitFromSecondScreen()));

        final int columns = 3;
        LinearLayout row = null;
        for (int i = 0; i < buttons.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(92), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(buttons.get(i), lp);
        }
        int rest = buttons.size() % columns;
        for (int i = 0; rest > 0 && i < columns - rest; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(92), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(new View(ctx), lp);
        }
        if (!buttons.isEmpty()) mFirstButton = buttons.get(0);

        // Floating buttons: search + add ROMs, bottom right (same place as on the main screen)
        LinearLayout fabs = new LinearLayout(ctx);
        fabs.setOrientation(LinearLayout.HORIZONTAL);
        fabs.addView(fab(ctx, ctx.getDrawable(android.R.drawable.ic_menu_search),
                ctx.getString(R.string.actionSearchRoms), v -> openSearch()));
        final MenuItem addItem = addRoms;
        View add = fab(ctx, ctx.getDrawable(R.drawable.ic_fab_refresh_roms),
                ctx.getString(R.string.menuItem_refreshRoms), v -> {
                    if (addItem != null) mActivity.onOptionsItemSelected(addItem);
                    else mActivity.onFabRefreshRomsClick(v);
                });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        addLp.leftMargin = dp(14);
        fabs.addView(add, addLp);
        ((LinearLayout.LayoutParams) fabs.getChildAt(0).getLayoutParams()).width = dp(56);
        ((LinearLayout.LayoutParams) fabs.getChildAt(0).getLayoutParams()).height = dp(56);
        LayoutParams fabsLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        fabsLp.setMargins(0, 0, dp(16), dp(16));
        addView(fabs, fabsLp);

        // Popup window for sub-options
        mPopup = new FrameLayout(ctx);
        mPopup.setBackgroundColor(0xB0000000);
        mPopup.setClickable(true);
        mPopup.setOnClickListener(v -> closePopup());
        mPopup.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(0xF2262626, dp(18), 0x66FFFFFF));
        card.setClickable(true); // don't close when tapping inside
        card.setPadding(dp(6), dp(6), dp(6), dp(10));
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(6), dp(4), dp(6));
        mPopupTitle = label(ctx, "", 18, TEXT, true);
        head.addView(mPopupTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = label(ctx, "✕", 20, MUTED, true);
        close.setPadding(dp(12), dp(4), dp(12), dp(4));
        close.setOnClickListener(v -> closePopup());
        head.addView(close);
        card.addView(head);
        ScrollView listScroll = new ScrollView(ctx);
        mPopupList = new LinearLayout(ctx);
        mPopupList.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(mPopupList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(listScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LayoutParams cardLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cardLp.setMargins(dp(18), dp(18), dp(18), dp(18));
        mPopup.addView(card, cardLp);
        addView(mPopup, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View optionButton(Context ctx, Drawable icon, CharSequence title, View.OnClickListener click)
    {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(4), dp(8), dp(4), dp(6));
        b.setBackground(buttonBackground(dp(16)));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        if (icon != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(icon.getConstantState() != null ? icon.getConstantState().newDrawable().mutate() : icon);
            iv.setImageTintList(ColorStateList.valueOf(TEXT));
            b.addView(iv, new LinearLayout.LayoutParams(dp(32), dp(32)));
        }
        TextView t = label(ctx, title, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, dp(6), 0, 0);
        b.addView(t);
        return b;
    }

    private View fab(Context ctx, Drawable icon, String description, View.OnClickListener click)
    {
        ImageView f = new ImageView(ctx);
        f.setImageDrawable(icon);
        f.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        f.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        f.setPadding(dp(15), dp(15), dp(15), dp(15));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, oval(FOCUS));
        bg.addState(new int[]{android.R.attr.state_focused}, oval(FOCUS));
        bg.addState(new int[]{}, oval(ACCENT));
        f.setBackground(bg);
        f.setElevation(dp(5));
        f.setContentDescription(description);
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnClickListener(click);
        return f;
    }

    private View popupRow(Context ctx, MenuItem item)
    {
        TextView t = label(ctx, item.getTitle(), 16, TEXT, false);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(buttonBackground(dp(10)));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            closePopup();
            mActivity.onOptionsItemSelected(item);
        });
        return t;
    }

    // ---------------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------------

    private void onOption(MenuItem item)
    {
        if (item.hasSubMenu() && item.getSubMenu() != null) {
            showPopup(item);
        } else {
            mActivity.onOptionsItemSelected(item);
        }
    }

    private void showPopup(MenuItem group)
    {
        mShowingExpansions = false;
        Context ctx = getContext();
        mPopupTitle.setText(group.getTitle());
        mPopupList.removeAllViews();
        Menu sub = group.getSubMenu();
        View first = null;
        for (int i = 0; i < sub.size(); i++) {
            MenuItem item = sub.getItem(i);
            if (!item.isVisible()) continue;
            View row = popupRow(ctx, item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), dp(2), dp(4), dp(2));
            mPopupList.addView(row, lp);
            if (first == null) first = row;
        }
        mPopup.setVisibility(View.VISIBLE);
        if (first != null) first.requestFocus();
    }

    /** Expansions popup: installed .exp files (with Remove) and "Import expansion…". */
    private void showExpansions()
    {
        Context ctx = getContext();
        mPopupTitle.setText(R.string.expansions_title);
        mPopupList.removeAllViews();
        mShowingExpansions = true;

        TextView importRow = label(ctx, ctx.getString(R.string.expansions_import), 16, TEXT, true);
        importRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        importRow.setBackground(buttonBackground(dp(10)));
        importRow.setFocusable(true);
        importRow.setClickable(true);
        importRow.setOnClickListener(v -> {
            ExpansionImportActivity.sOnChanged = () -> { if (mShowingExpansions && mPopup.getVisibility() == View.VISIBLE) showExpansions(); };
            SecondScreen.startActivity(mActivity, new Intent(mActivity, ExpansionImportActivity.class));
        });
        mPopupList.addView(importRow, popupLp());

        List<Expansion> installed = ExpansionManager.list(ctx);
        if (installed.isEmpty()) {
            TextView none = label(ctx, ctx.getString(R.string.expansions_none), 14, MUTED, false);
            none.setPadding(dp(16), dp(12), dp(16), dp(8));
            mPopupList.addView(none, popupLp());
        }
        for (Expansion e : installed) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(8), dp(8));
            row.setBackground(rounded(0x33FFFFFF, dp(10), 0));
            LinearLayout text = new LinearLayout(ctx);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(label(ctx, e.name, 16, TEXT, true));
            text.addView(label(ctx, ctx.getString(R.string.expansions_for, e.game, e.version)
                    + (e.author.isEmpty() ? "" : " · " + e.author), 12, MUTED, false));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView remove = label(ctx, ctx.getString(R.string.expansions_remove), 14, 0xFFFF8A80, true);
            remove.setPadding(dp(12), dp(10), dp(12), dp(10));
            remove.setBackground(buttonBackground(dp(10)));
            remove.setFocusable(true);
            remove.setClickable(true);
            remove.setOnClickListener(v -> {
                if (ExpansionManager.delete(e)) {
                    Toast.makeText(ctx, ctx.getString(R.string.expansions_removed, e.name), Toast.LENGTH_SHORT).show();
                }
                showExpansions();
            });
            row.addView(remove);
            mPopupList.addView(row, popupLp());
        }
        mPopup.setVisibility(View.VISIBLE);
        importRow.requestFocus();
    }

    private boolean mShowingExpansions = false;

    private LinearLayout.LayoutParams popupLp()
    {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(3), dp(4), dp(3));
        return lp;
    }

    private void closePopup()
    {
        mShowingExpansions = false;
        mPopup.setVisibility(View.GONE);
    }

    private void openSearch()
    {
        mSearchBar.setVisibility(View.VISIBLE);
        mSearchField.requestFocus();
        InputMethodManager imm = (InputMethodManager) mSearchField.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mSearchField, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch()
    {
        mSearchField.setText("");
        mSearchBar.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) mSearchField.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mSearchField.getWindowToken(), 0);
    }

    /** Back: close the popup, then the search bar. Returns true if something was closed. */
    public boolean handleBack()
    {
        if (mPopup.getVisibility() == View.VISIBLE) {
            closePopup();
            return true;
        }
        if (mSearchBar.getVisibility() == View.VISIBLE) {
            closeSearch();
            return true;
        }
        return false;
    }

    @Override
    public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect)
    {
        if (mPopup.getVisibility() == View.VISIBLE && mPopupList.getChildCount() > 0) {
            return mPopupList.getChildAt(0).requestFocus();
        }
        if (mFirstButton != null) return mFirstButton.requestFocus();
        return super.requestFocus(direction, previouslyFocusedRect);
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------------------------------

    private TextView label(Context ctx, CharSequence text, int sp, int color, boolean bold)
    {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, int radius, int stroke)
    {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    private GradientDrawable oval(int color)
    {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Translucent card, highlighted when focused (controller) or pressed. */
    private StateListDrawable buttonBackground(int radius)
    {
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable on = rounded(0x803249BC, radius, FOCUS);
        on.setStroke(dp(2.5f), FOCUS);
        bg.addState(new int[]{android.R.attr.state_pressed}, on);
        bg.addState(new int[]{android.R.attr.state_focused}, on);
        bg.addState(new int[]{}, rounded(0x59000000, radius, 0x40FFFFFF));
        return bg;
    }
}
