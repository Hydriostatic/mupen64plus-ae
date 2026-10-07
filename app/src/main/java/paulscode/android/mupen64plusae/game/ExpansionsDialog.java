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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import java.util.List;

import paulscode.android.mupen64plusae.R;

/**
 * Main screen "Expansions (.exp)": lists the installed expansions, imports a new one and removes
 * one (tap it). A game with a matching expansion shows its panel on the second screen.
 */
public final class ExpansionsDialog
{
    private ExpansionsDialog() {}

    public static void show(@NonNull Activity activity)
    {
        final List<Expansion> installed = ExpansionManager.list(activity);
        String[] rows = new String[installed.size()];
        for (int i = 0; i < rows.length; i++) {
            Expansion e = installed.get(i);
            rows[i] = e.name + "\n" + activity.getString(R.string.expansions_for, e.game, e.version);
        }

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(R.string.expansions_title)
                .setPositiveButton(R.string.expansions_import, (d, w) -> startImport(activity))
                .setNegativeButton(android.R.string.cancel, null);
        if (rows.length == 0) {
            b.setMessage(R.string.expansions_none);
        } else {
            b.setItems(rows, (d, which) -> confirmRemove(activity, installed.get(which)));
        }
        b.setOnDismissListener(d -> {
            for (Expansion e : installed) e.close();
        });
        b.show();
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
