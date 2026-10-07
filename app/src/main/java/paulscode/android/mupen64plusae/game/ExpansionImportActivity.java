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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import paulscode.android.mupen64plusae.R;

import java.io.IOException;

/**
 * "Import expansion (.exp)": opens the system file picker, checks the chosen .exp and stores it
 * in the app.
 */
public class ExpansionImportActivity extends Activity
{
    private static final int PICK = 1;

    /** Called (on the main thread) after an expansion was imported or removed. */
    public static Runnable sOnChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // picker already open
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*"); // .exp has no registered MIME type
        try {
            startActivityForResult(pick, PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.expansions_noPicker, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = data != null ? data.getData() : null;
        if (requestCode != PICK || resultCode != RESULT_OK || uri == null) {
            finish();
            return;
        }
        final android.content.Context app = getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                Expansion e = ExpansionManager.importFromUri(app, uri);
                message = getString(R.string.expansions_imported, e.name);
                e.close();
            } catch (IOException ex) {
                message = getString(R.string.expansions_importFailed, ex.getMessage());
            }
            final String m = message;
            main.post(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (sOnChanged != null) sOnChanged.run();
                finish();
            });
        }, "ExpansionImport").start();
    }
}
