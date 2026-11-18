package com.orbising.orbis.plugin.sdk;

import com.orbising.orbis.plugin.sdk.IPluginReply;

/** A binding-scoped host endpoint, restricted to explicitly granted host actions. */
oneway interface IOrbisHost {
    void request(String envelopeJson, IPluginReply reply);
    void cancel(String bindingId, String requestId);
}
