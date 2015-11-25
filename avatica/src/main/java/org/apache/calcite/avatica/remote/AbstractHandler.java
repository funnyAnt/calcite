/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.avatica.remote;

import org.apache.calcite.avatica.AvaticaSeverity;
import org.apache.calcite.avatica.NoSuchConnectionException;
import org.apache.calcite.avatica.remote.Service.ErrorResponse;
import org.apache.calcite.avatica.remote.Service.Request;
import org.apache.calcite.avatica.remote.Service.Response;
import org.apache.calcite.avatica.remote.Service.RpcMetadataResponse;

import java.io.IOException;

/**
 * Abstract base class for {@link Handler}s to extend to inherit functionality common across
 * serialization strategies.
 *
 * @param <T> The format Requests/Responses are serialized as.
 */
public abstract class AbstractHandler<T> implements Handler<T> {
  private static final String NULL_EXCEPTION_MESSAGE = "(null exception message)";
  protected final Service service;
  private RpcMetadataResponse metadata = null;

  public AbstractHandler(Service service) {
    this.service = service;
  }

  abstract Request decode(T serializedRequest) throws IOException;

  /**
   * Serialize the given {@link Response} per the concrete {@link Handler} implementation.
   *
   * @param response The {@link Response} to serialize.
   * @return A serialized representation of the {@link Response}.
   * @throws IOException
   */
  abstract T encode(Response response) throws IOException;

  /**
   * Unwrap Avatica-specific context about a given exception.
   *
   * @param e A caught exception throw by Avatica implementation.
   * @return An {@link ErrorResponse}.
   */
  ErrorResponse unwrapException(Exception e) {
    // By default, we know nothing extra.
    int errorCode = ErrorResponse.UNKNOWN_ERROR_CODE;
    String sqlState = ErrorResponse.UNKNOWN_SQL_STATE;
    AvaticaSeverity severity = AvaticaSeverity.UNKNOWN;
    String errorMsg = null;

    // Extract the contextual information if we have it. We may not.
    if (e instanceof AvaticaRuntimeException) {
      AvaticaRuntimeException rte = (AvaticaRuntimeException) e;
      errorCode = rte.getErrorCode();
      sqlState = rte.getSqlState();
      severity = rte.getSeverity();
      errorMsg = rte.getErrorMessage();
    } else if (e instanceof NoSuchConnectionException) {
      errorCode = ErrorResponse.MISSING_CONNECTION_ERROR_CODE;
      severity = AvaticaSeverity.ERROR;
      errorMsg = e.getMessage();
    } else {
      // Try to construct a meaningful error message when the server impl doesn't provide one.
      errorMsg = getCausalChain(e);
    }

    return new ErrorResponse(e, errorMsg, errorCode, sqlState, severity, metadata);
  }

  /**
   * Compute a response for the given request, handling errors generated by that computation.
   *
   * @param serializedRequest The caller's request.
   * @return A {@link Response} with additional context about that response.
   */
  public HandlerResponse<T> apply(T serializedRequest) {
    final Service.Request request;
    try {
      request = decode(serializedRequest);
    } catch (IOException e) {
      // TODO provide a canned ErrorResponse.
      throw new RuntimeException(e);
    }

    try {
      final Service.Response response = request.accept(service);
      return new HandlerResponse<>(encode(response), HTTP_OK);
    } catch (Exception e) {
      ErrorResponse errorResp = unwrapException(e);

      try {
        return new HandlerResponse<>(encode(errorResp), HTTP_INTERNAL_SERVER_ERROR);
      } catch (IOException e1) {
        // TODO provide a canned ErrorResponse

        // If we can't serialize error message to JSON, can't give a meaningful error to caller.
        // Just try to not unnecessarily create more exceptions.
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }

        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Constructs a message for the summary of an Exception.
   *
   * @param e The Exception to summarize.
   * @return A summary message for the Exception.
   */
  private String getCausalChain(Exception e) {
    StringBuilder sb = new StringBuilder(16);
    Throwable curr = e;
    // Could use Guava, but that would increase dependency set unnecessarily.
    while (null != curr) {
      if (sb.length() > 0) {
        sb.append(" -> ");
      }
      String message = curr.getMessage();
      sb.append(curr.getClass().getSimpleName()).append(": ");
      sb.append(null == message ? NULL_EXCEPTION_MESSAGE : message);
      curr = curr.getCause();
    }
    if (sb.length() == 0) {
      // Catch the case where we have no error message.
      return "Unknown error message";
    }
    return sb.toString();
  }

  @Override
  public void setRpcMetadata(RpcMetadataResponse metadata) {
    this.metadata = metadata;
  }
}

// End AbstractHandler.java
