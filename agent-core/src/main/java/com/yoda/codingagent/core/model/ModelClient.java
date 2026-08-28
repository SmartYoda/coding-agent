package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.api.CancellationToken;

public interface ModelClient {

    void stream(ModelRequest request, ModelStreamSink sink, CancellationToken token);
}
