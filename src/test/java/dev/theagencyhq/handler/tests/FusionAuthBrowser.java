/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */
package dev.theagencyhq.handler.tests;

import module java.base;
import module java.net.http;
import module dev.theagencyhq.handler;

/**
 * A {@link Browser} that completes the FusionAuth login over HTTP: it fetches the authorize page, submits the login
 * form with the given credentials, and follows the resulting redirects (login &rarr; consent &rarr; the loopback
 * callback), which delivers the authorization code to the loopback server. This replaces the human-driven browser
 * step so the flow can run unattended.
 *
 * @author Brian Pontarelli
 */
public final class FusionAuthBrowser implements Browser {
  private static final Pattern FORM_ACTION = Pattern.compile("<form\\b[^>]*\\baction=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
  private static final Pattern INPUT_TAG = Pattern.compile("<input\\b[^>]*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern NAME_ATTR = Pattern.compile("\\bname=\"([^\"]*)\"");
  private static final Pattern VALUE_ATTR = Pattern.compile("\\bvalue=\"([^\"]*)\"");
  private final String loginId;
  private final String password;

  public FusionAuthBrowser(String loginId, String password) {
    this.loginId = loginId;
    this.password = password;
  }

  private static String formEncode(Map<String, String> fields) {
    StringBuilder builder = new StringBuilder();
    fields.forEach((name, value) -> {
      if (!builder.isEmpty()) {
        builder.append('&');
      }
      builder.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
             .append('=')
             .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    });
    return builder.toString();
  }

  private static Map<String, String> hiddenInputs(String html) {
    Map<String, String> fields = new LinkedHashMap<>();
    Matcher inputMatcher = INPUT_TAG.matcher(html);
    while (inputMatcher.find()) {
      String tag = inputMatcher.group();
      if (!tag.contains("type=\"hidden\"")) {
        continue;
      }
      Matcher nameMatcher = NAME_ATTR.matcher(tag);
      if (!nameMatcher.find()) {
        continue;
      }
      Matcher valueMatcher = VALUE_ATTR.matcher(tag);
      fields.put(nameMatcher.group(1), valueMatcher.find() ? valueMatcher.group(1) : "");
    }
    return fields;
  }

  @Override
  public void open(String url, PrintStream out) {
    CookieManager cookieManager = new CookieManager();
    cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);

    try (HttpClient client = HttpClient.newBuilder()
                                       .cookieHandler(cookieManager)
                                       .followRedirects(HttpClient.Redirect.NORMAL)
                                       .connectTimeout(Duration.ofSeconds(10))
                                       .build()) {
      // Fetch the hosted login page and scrape its form.
      HttpResponse<String> page = client.send(
          HttpRequest.newBuilder(URI.create(url)).GET().build(),
          HttpResponse.BodyHandlers.ofString()
      );
      String html = page.body();

      Matcher actionMatcher = FORM_ACTION.matcher(html);
      if (!actionMatcher.find()) {
        throw new RuntimeException("Could not find the login form in the FusionAuth page.");
      }
      URI postURI = URI.create(url).resolve(actionMatcher.group(1));

      // Carry forward every hidden field FusionAuth embedded (OAuth context, tenant, etc.) and add the credentials.
      Map<String, String> fields = hiddenInputs(html);
      fields.put("loginId", loginId);
      fields.put("password", password);

      // Submit the form. The client follows login -> consent -> the loopback callback automatically.
      client.send(
          HttpRequest.newBuilder(postURI)
                     .header("Content-Type", "application/x-www-form-urlencoded")
                     .POST(HttpRequest.BodyPublishers.ofString(formEncode(fields)))
                     .build(),
          HttpResponse.BodyHandlers.ofString()
      );
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException("Headless FusionAuth login failed. Message was [" + e.getMessage() + "]", e);
    }
  }
}
