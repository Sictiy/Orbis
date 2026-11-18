package com.orbising.orbis.plugin.sdk;

import com.orbising.orbis.plugin.sdk.IPluginReply;
import com.orbising.orbis.plugin.sdk.IOrbisHost;

/** The host and the plugin must authenticate the calling UID in every transaction. */
oneway interface IOrbisPlugin {
    void openBinding(String envelopeJson, IOrbisHost host, IPluginReply reply);
    void request(String envelopeJson, IPluginReply reply);
    void cancel(String bindingId, String requestId);
}
