/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.agency;

import module java.base;
import module java.net.http;

/**
 * The Agency API client. Every network and protocol failure is converted into a result — this class never throws,
 * because an unavailable Agency must never stop the Handler from distributing what it already has.
 *
 * @author Brian Pontarelli
 */
public class AgencyClient {
  public static final String BRIEFING_PATH = "/api/v1/briefing";
  public static final String ORGANIZATION_PATH = "/api/v1/organization";
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
    byte[] body;
    try {
      body = new BriefingRequest(currentVersions).toJSONBytes();
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("Unable to build the briefing request: " + e.getMessage(), false);
    }

    return switch (exchange(() -> request(BRIEFING_PATH).header("Content-Type", "application/json")
                                                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)))) {
      case Attempt.Failure(String reason, boolean authenticationFailure) ->
          new BriefingResult.Failed(reason, authenticationFailure);
      case Attempt.Response(HttpResponse<byte[]> response) -> switch (response.statusCode()) {
        case 200 -> parseBriefing(response.body());
        case 304 -> new BriefingResult.NotModified();
        case 403 -> new BriefingResult.Forbidden();
        default -> new BriefingResult.Failed("The Agency returned status [" + response.statusCode() + "]", false);
      };
    };
  }

  public OrganizationResult organizations() {
    return switch (exchange(() -> request(ORGANIZATION_PATH).GET())) {
      case Attempt.Failure(String reason, boolean ignored) -> new OrganizationResult.Failed(reason);
      case Attempt.Response(HttpResponse<byte[]> response) -> switch (response.statusCode()) {
        case 200 -> parseOrganizations(response.body());
        default -> new OrganizationResult.Failed("The Agency returned status [" + response.statusCode() + "]");
      };
    };
  }

  /**
   * Sends the request once, then once more after a refresh when The Agency answers 401. The retry's own 401 is
   * terminal — a refreshed token the Agency still rejects means re-authentication, not another loop.
   *
   * @param requests Builds a fresh request per attempt, so the retry picks up the refreshed bearer token.
   * @return The final response, or the failure it should be reported as.
   */
  private Attempt exchange(Supplier<HttpRequest.Builder> requests) {
    Attempt attempt = send(requests);
    if (!(attempt instanceof Attempt.Response(HttpResponse<byte[]> response)) || response.statusCode() != 401) {
      return attempt;
    }

    if (!tokens.refresh()) {
      return new Attempt.Failure("The Agency rejected the access token. Run [handler login].", true);
    }

    attempt = send(requests);
    if (attempt instanceof Attempt.Response(HttpResponse<byte[]> retried) && retried.statusCode() == 401) {
      return new Attempt.Failure("The Agency rejected the refreshed access token. Run [handler login].", true);
    }

    return attempt;
  }

  private BriefingResult parseBriefing(byte[] body) {
    try {
      BriefingResponse response = BriefingResponse.fromJSON(body);
      return new BriefingResult.Updated(response.organizationIds(), response.briefs());
    } catch (RuntimeException e) {
      return new BriefingResult.Failed("The Agency returned a malformed briefing response: " + e.getMessage(), false);
    }
  }

  private OrganizationResult parseOrganizations(byte[] body) {
    try {
      return new OrganizationResult.Loaded(OrganizationResponse.fromJSON(body).organizations());
    } catch (RuntimeException e) {
      return new OrganizationResult.Failed("The Agency returned a malformed organization response: " + e.getMessage());
    }
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(theAgencyURL + path))
                      .header("Authorization", "Bearer " + tokens.bearerToken())
                      .timeout(REQUEST_TIMEOUT);
  }

  private Attempt send(Supplier<HttpRequest.Builder> requests) {
    HttpRequest request;
    try {
      request = requests.get().build();
    } catch (RuntimeException e) {
      return new Attempt.Failure("Unable to build the request to The Agency: " + e.getMessage(), false);
    }

    try {
      return new Attempt.Response(httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Attempt.Failure("The request to The Agency was interrupted", false);
    } catch (IOException e) {
      return new Attempt.Failure("The Agency at [" + theAgencyURL + "] is unreachable: " + e.getMessage(), false);
    }
  }

  private sealed interface Attempt {
    record Failure(String reason, boolean authenticationFailure) implements Attempt {
    }

    record Response(HttpResponse<byte[]> response) implements Attempt {
    }
  }
}
