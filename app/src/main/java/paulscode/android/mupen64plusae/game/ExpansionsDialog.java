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

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.util.List;

import paulscode.android.mupen64plusae.R;

/**
 * Main screen "Expansions (.exp)": lists the installed expansions, imports a new one, removes
 * one (tap it) and updates one from GitHub (the button next to it). A game with a matching expansion shows its panel on the second screen.
 */
public final class ExpansionsDialog
{
    private ExpansionsDialog() {}

    public static void show(@NonNull Activity activity)
    {
        final List<Expansion> installed = ExpansionManager.list(activity);
        final AlertDialog[] dialog = new AlertDialog[1];

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(R.string.expansions_title)
                .setPositiveButton(R.string.expansions_import, (d, w) -> startImport(activity))
                .setNegativeButton(android.R.string.cancel, null);
        if (installed.isEmpty()) {
            b.setMessage(R.string.expansions_none);
        } else {
            b.setView(buildList(activity, installed, dialog));
        }
        b.setOnDismissListener(d -> {
            for (Expansion e : installed) e.close();
        });
        dialog[0] = b.show();
    }

    /**
     * One row per expansion: its name (tap = remove) and a small update button on the right
     * (looks for a newer copy on GitHub). Plain views, so the D-pad moves between them too.
     */
    private static View buildList(@NonNull Activity activity, @NonNull List<Expansion> installed,
                                  @NonNull AlertDialog[] dialog)
    {
        float dp = activity.getResources().getDisplayMetrics().density;
        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        int rowBg = tv.resourceId;
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        int btnBg = tv.resourceId;

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) (8 * dp), 0, 0);

        for (final Expansion e : installed) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView text = new TextView(activity);
            text.setText(e.name + "\n" + activity.getString(R.string.expansions_for, e.game, e.version));
            text.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
            text.setPadding((int) (24 * dp), (int) (10 * dp), (int) (8 * dp), (int) (10 * dp));
            text.setBackgroundResource(rowBg);
            text.setFocusable(true);
            text.setClickable(true);
            text.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                confirmRemove(activity, e);
            });
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final ImageButton update = new ImageButton(activity);
            update.setImageResource(R.drawable.ic_refresh);
            update.setColorFilter(text.getCurrentTextColor()); // readable on light and dark themes
            update.setScaleType(ImageView.ScaleType.FIT_CENTER);
            update.setPadding((int) (10 * dp), (int) (10 * dp), (int) (10 * dp), (int) (10 * dp));
            update.setBackgroundResource(btnBg);
            update.setContentDescription(activity.getString(R.string.expansions_update));
            update.setFocusable(true);
            final ProgressBar busy = new ProgressBar(activity);
            busy.setVisibility(View.GONE);
            int size = (int) (48 * dp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd((int) (16 * dp));
            row.addView(update, lp);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(size, size);
            lp2.setMarginEnd((int) (16 * dp));
            row.addView(busy, lp2);

            update.setOnClickListener(v -> {
                update.setVisibility(View.GONE);
                busy.setVisibility(View.VISIBLE);
                ExpansionUpdater.update(activity, e.id, e.version, e.updateSource, e.file, result -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    busy.setVisibility(View.GONE);
                    update.setVisibility(View.VISIBLE);
                    String msg;
                    switch (result.outcome) {
                        case UPDATED:
                            msg = activity.getString(R.string.expansions_updated, e.name, result.detail);
                            break;
                        case UP_TO_DATE:
                            msg = activity.getString(R.string.expansions_upToDate, e.name, e.version);
                            break;
                        case REMOTE_OLDER:
                            msg = activity.getString(R.string.expansions_remoteOlder, result.detail, e.version);
                            break;
                        case NOT_FOUND:
                            msg = activity.getString(R.string.expansions_notFound, e.id);
                            break;
                        default:
                            msg = activity.getString(R.string.expansions_updateFailed, result.detail);
                    }
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    if (result.outcome == ExpansionUpdater.Outcome.UPDATED) {
                        // Show the list again with the new version
                        if (dialog[0] != null && dialog[0].isShowing()) dialog[0].dismiss();
                        show(activity);
                    }
                });
            });
            list.addView(row);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        return scroll;
    }

    /** Open the file picker; when the import finishes the list shows again with the new expansion. */
    public static void startImport(@NonNull Activity activity)
    {
        ExpansionImportActivity.sOnChanged = () -> {
            ExpansionImportActivity.sOnChanged = null;
            if (!activity.isFinishing() && !activity.isDestroyed()) show(activity);
        };
        activity.startActivity(new Intent(activity, ExpansionImportActivity.class));
    }

    private static void confirmRemove(@NonNull Activity activity, @NonNull Expansion e)
    {
        new AlertDialog.Builder(activity)
                .setTitle(e.name)
                .setMessage(activity.getString(R.string.expansions_for, e.game, e.version))
                .setPositiveButton(R.string.expansions_remove, (d, w) -> {
                    String name = e.name;
                    if (ExpansionManager.delete(e)) {
                        Toast.makeText(activity, activity.getString(R.string.expansions_removed, name),
                                Toast.LENGTH_SHORT).show();
                    }
                    show(activity);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> show(activity))
                .show();
    }
}
