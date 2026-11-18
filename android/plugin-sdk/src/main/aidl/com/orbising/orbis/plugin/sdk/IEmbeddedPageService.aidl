package com.orbising.orbis.plugin.sdk;

import com.orbising.orbis.plugin.sdk.IEmbeddedPageCallback;
import android.os.Bundle;

/** Optional, separately bound endpoint. The plugin must validate the caller and binding. */
oneway interface IEmbeddedPageService {
    void openPage(String bindingId, String requestId, IBinder hostToken, int displayId, int width, int height, in Bundle pageContext, IEmbeddedPageCallback callback);
}
