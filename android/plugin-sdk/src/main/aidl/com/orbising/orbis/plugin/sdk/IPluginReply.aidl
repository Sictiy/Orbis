package com.orbising.orbis.plugin.sdk;

/** Receives a bounded response envelope. The recipient authenticates every call. */
oneway interface IPluginReply {
    void onMessage(String envelopeJson);
}
