package io.jafra.analyzer.v2.api;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class V2ExceptionMapper implements ExceptionMapper<V2Exception> {
    @Override
    public Response toResponse(V2Exception error) {
        return Response.status(error.status()).entity(error.body()).build();
    }
}
