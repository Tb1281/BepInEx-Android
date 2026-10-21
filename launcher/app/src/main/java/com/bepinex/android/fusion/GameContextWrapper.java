package com.bepinex.android.fusion;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Display;

import androidx.annotation.Nullable;

import java.io.File;

/**
 * Direct port of FusionCore's CustomContextWrapper.
 * Used to wrap the UnityPlayer constructor argument.
 */
public class GameContextWrapper extends ContextWrapper {
    Context fusionContext;
    Context appContext;
    private final String gamePackage;

    public GameContextWrapper(Context gameContext, Context fusionContext, Context appContext) {
        super(gameContext);
        this.fusionContext = fusionContext;
        this.gamePackage = gameContext.getPackageName();
        this.getApplicationInfo().dataDir = appContext.getApplicationInfo().dataDir;
        // Prevents the game from resolving its own libraries
        this.getApplicationInfo().nativeLibraryDir = "";
        this.appContext = appContext != fusionContext
                ? new GameContextWrapper(this, appContext, appContext)
                : fusionContext;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return this.fusionContext.getSharedPreferences(name, mode);
    }

    public boolean deleteSharedPreferences(String name) {
        return this.fusionContext.deleteSharedPreferences(name);
    }

    public boolean moveSharedPreferencesFrom(Context sourceContext, String name) {
        return this.fusionContext.moveSharedPreferencesFrom(sourceContext, name);
    }

    // Storage is scoped per game (see PerGameStorage): the hosted game must
    // not see the launcher's raw storage roots, which all games would share.
    @Override
    public File getFilesDir() {
        return PerGameStorage.filesDir(this.fusionContext, this.gamePackage);
    }

    @Override
    public File getCacheDir() {
        return PerGameStorage.cacheDir(this.fusionContext, this.gamePackage);
    }

    @Nullable
    @Override
    public File getExternalCacheDir() {
        return PerGameStorage.externalCacheDir(this.fusionContext, this.gamePackage);
    }

    @Override
    public File[] getExternalCacheDirs() {
        File dir = getExternalCacheDir();
        return dir != null ? new File[]{dir} : this.fusionContext.getExternalCacheDirs();
    }

    @Override
    public File getExternalFilesDir(String type) {
        return PerGameStorage.externalFilesDir(this.fusionContext, this.gamePackage, type);
    }

    @Override
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return this.fusionContext.getDisplay();
        }
        return null;
    }

    @Override
    public Object getSystemService(String name) {
        return this.fusionContext.getSystemService(name);
    }

    @Override
    public Context getBaseContext() {
        return super.getBaseContext();
    }

    @Override
    public Context getApplicationContext() {
        if (appContext != null) {
            Context realApp = appContext.getApplicationContext();
            if (realApp != null) {
                return realApp;
            }
        }
        return appContext;
    }

    @Override
    public File getObbDir() {
        return null;
    }

    @Override
    public File[] getObbDirs() {
        return this.fusionContext.getObbDirs();
    }
}
