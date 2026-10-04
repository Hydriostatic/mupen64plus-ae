package paulscode.android.mupen64plusae.game;

import android.app.Activity;
import android.os.Bundle;

/**
 * Settings > Input > Jet Force Gemini camera: the same panel as in the game, outside of it.
 * Saved values apply the next time the game starts (in the game, the second screen panel
 * applies them at once).
 */
public class JetForceGeminiCameraSettingsActivity extends Activity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(new JetForceGeminiCameraPanel(this, false));
    }
}
