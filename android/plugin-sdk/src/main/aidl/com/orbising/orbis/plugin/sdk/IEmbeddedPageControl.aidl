package com.orbising.orbis.plugin.sdk;

import android.os.Bundle;

/** A page control belongs to one authenticated binding and one page instance. */
oneway interface IEmbeddedPageControl {
    void resize(int width, int height);
    void close();
    void back();
    void activityLaunchFailed();
    void setDarkTheme(boolean dark);
    void setTheme(in Bundle theme);
    /** An action is scoped to one sheet instance and becomes invalid when it changes. */
    void sheetAction(String sheetId, String actionId);
}
