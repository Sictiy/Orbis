package com.orbising.orbis.plugin.sdk;

import android.os.Bundle;
import android.app.PendingIntent;
import com.orbising.orbis.plugin.sdk.IEmbeddedPageControl;

/** The host verifies the caller identity and correlates requestId before accepting a surface. */
oneway interface IEmbeddedPageCallback {
    void onOpened(String requestId, in Bundle surface, IEmbeddedPageControl control);
    void onError(String requestId);
    void onRequestActivity(String requestId, in PendingIntent activity);
    void onBackStateChanged(String requestId, boolean canGoBack);
    /** Optional host-rendered sheet; null closes the current sheet. */
    void onSheetChanged(String requestId, in Bundle sheet);
    /** Request an explicit Activity belonging to the approved plugin package. */
    void onRequestOwnActivity(String requestId, in Bundle request);
}
