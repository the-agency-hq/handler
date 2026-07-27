/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module java.net.http;

/**
 * The Agency API client. Every network and protocol failure is converted into a {@link BriefingResult} — this class
 * never throws, because an unavailable Agency must never stop the Handler from distributing what it already has.
 *
 * @author Brian Pontarelli
 */
public class AgencyClient {
  public static final String BRIEFING_PATH = "/api/v1/briefing";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final String theAgencyURL;
  private final TokenSupplier tokens;

  public AgencyClient(String theAgencyURL, TokenSupplier tokens) {
    this.theAgencyURL = theAgencyURL;
    this.tokens = tokens;
    this.httpClient = HttpClient.newBuilder()
                                .connectTimeout(CONNECT_TIMEOUT)
                                .build();
  }

  public BriefingResult briefing(List<CurrentVersion> currentVersions) {
    HttpRequest request;
    try {
      request = HttpRequest.newBuilder(URI.create(theAgencyURL + BRIEFING_PATH))
                           .header("Authorization", "Bearer " + tokens.bearerToken())
                           .header("Content-Type", "application/json")
                           .timeout(REQUEST_TIMEOUT)
                           .POST(HttpRequest.BodyPublishers.ofByteArray(new BriefingRequest(currentVersions)
                                                                            .toJSONBytes()))
                           .build();
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("Unable to build the briefing request: " + e.getMessage(), false);
    }

    HttpResponse<byte[]> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new BriefingResult.Failed("The briefing request was interrupted", false);
    } catch (IOException e) {
      return new BriefingResult.Failed("The Agency at [" + theAgencyURL + "] is unreachable: " + e.getMessage(), false);
    }

    return switch (response.statusCode()) {
      case 200 -> parse(response.body());
      case 304 -> new BriefingResult.NotModified();
      case 401 -> new BriefingResult.Failed("The Agency rejected the access token", true);
      case 403 -> new BriefingResult.Forbidden();
      default -> new BriefingResult.Failed("The Agency returned status [" + response.statusCode() + "]", false);
    };
  }

  private BriefingResult parse(byte[] body) {
    try {
      BriefingResponse response = BriefingResponse.fromJSON(body);
      return new BriefingResult.Updated(response.organizationIds(), response.briefs());
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("The Agency returned a malformed briefing response: " + e.getMessage(), false);
    }
  }
}
